package pl.automine;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class AutoMineMod implements ClientModInitializer {
    public static final Set<Block> TARGETS = new LinkedHashSet<>();

    /** Strefa = prostopadłościan w jednym wymiarze. */
    private record Zone(String dim, int x1, int y1, int z1, int x2, int y2, int z2) {
        boolean contains(BlockPos p) {
            return p.getX() >= x1 && p.getX() <= x2
                    && p.getY() >= y1 && p.getY() <= y2
                    && p.getZ() >= z1 && p.getZ() <= z2;
        }
    }

    private static final List<Zone> zones = new ArrayList<>();
    private static boolean zoneMode = false;
    private static BlockPos pendingCorner = null;
    private static String pendingDim = "";
    private static String curDim = "";

    private static final int SCAN_RADIUS = 20;
    private static final double REACH = 4.2;

    private static KeyBinding openKey;
    private static KeyBinding toggleKey;
    private static KeyBinding zoneKey;
    private static KeyBinding modeKey;

    private static boolean active = false;
    private static BlockPos target = null;
    private static BlockPos lastStarted = null;
    private static int scanCooldown = 0;
    private static int stuckTicks = 0;
    private static int miningTicks = 0;
    private static Vec3d lastPos = Vec3d.ZERO;
    private static final Map<BlockPos, Long> blacklist = new HashMap<>();

    private static BlockPos scanBest;
    private static double scanBestD;

    @Override
    public void onInitializeClient() {
        load();
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.automine.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, "key.categories.automine"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.automine.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.automine"));
        zoneKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.automine.zone", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_Z, "key.categories.automine"));
        modeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.automine.mode", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_N, "key.categories.automine"));
        ClientTickEvents.START_CLIENT_TICK.register(AutoMineMod::tick);
    }

    // ---------- config ----------
    private static Path blocksPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("automine.txt");
    }

    private static Path zonesPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("automine_zones.txt");
    }

    public static void save() {
        List<String> lines = new ArrayList<>();
        for (Block b : TARGETS) lines.add(Registries.BLOCK.getId(b).toString());
        try {
            Files.write(blocksPath(), lines);
        } catch (IOException ignored) {
        }
    }

    private static void saveZones() {
        List<String> lines = new ArrayList<>();
        lines.add(zoneMode ? "mode=zones" : "mode=blocks");
        for (Zone z : zones) {
            lines.add(z.dim() + " " + z.x1() + " " + z.y1() + " " + z.z1() + " " + z.x2() + " " + z.y2() + " " + z.z2());
        }
        try {
            Files.write(zonesPath(), lines);
        } catch (IOException ignored) {
        }
    }

    private static void load() {
        try {
            if (Files.exists(blocksPath())) {
                for (String line : Files.readAllLines(blocksPath())) {
                    Identifier id = Identifier.tryParse(line.trim());
                    if (id != null && Registries.BLOCK.containsId(id)) TARGETS.add(Registries.BLOCK.get(id));
                }
            }
            if (Files.exists(zonesPath())) {
                for (String line : Files.readAllLines(zonesPath())) {
                    line = line.trim();
                    if (line.startsWith("mode=")) {
                        zoneMode = line.equals("mode=zones");
                        continue;
                    }
                    String[] t = line.split(" ");
                    if (t.length != 7) continue;
                    try {
                        zones.add(new Zone(t[0], Integer.parseInt(t[1]), Integer.parseInt(t[2]), Integer.parseInt(t[3]),
                                Integer.parseInt(t[4]), Integer.parseInt(t[5]), Integer.parseInt(t[6])));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    // ---------- main loop ----------
    private static boolean isDown(MinecraftClient client, int a, int b) {
        long window = client.getWindow().getHandle();
        return InputUtil.isKeyPressed(window, a) || InputUtil.isKeyPressed(window, b);
    }

    private static void msg(MinecraftClient client, String text) {
        if (client.player != null) client.player.sendMessage(Text.literal(text), true);
    }

    private static void tick(MinecraftClient client) {
        if (client.world != null) curDim = client.world.getRegistryKey().getValue().toString();

        while (openKey.wasPressed()) {
            client.setScreen(new BlockSelectScreen());
        }
        while (toggleKey.wasPressed()) {
            boolean ctrl = isDown(client, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL);
            if (ctrl) {
                addLookedAtBlock(client);
            } else if (active) {
                stop(client, "AutoMine: wyłączony");
            } else {
                start(client);
            }
        }
        while (zoneKey.wasPressed()) {
            handleZoneKey(client);
        }
        while (modeKey.wasPressed()) {
            zoneMode = !zoneMode;
            saveZones();
            target = null;
            msg(client, zoneMode ? "AutoMine: tryb STREFY (kopie wszystko widoczne w strefach)"
                    : "AutoMine: tryb BLOKI (kopie wybrane bloki)");
        }
        if (!active) return;

        ClientPlayerEntity p = client.player;
        ClientWorld w = client.world;
        if (p == null || w == null || client.interactionManager == null) {
            active = false;
            return;
        }
        if (p.hurtTime > 0 || p.getHealth() < 6.0f) {
            stop(client, "AutoMine: zatrzymano (obrażenia / niskie HP)");
            return;
        }

        setKeys(client, false, false);
        if (client.currentScreen != null) return;

        if (target != null && !isValid(w, target, p.getEyePos())) {
            target = null;
            lastStarted = null;
            scanCooldown = 0; // od razu szukaj następnego bloku
        }
        if (target == null) {
            stuckTicks = 0;
            miningTicks = 0;
            if (--scanCooldown <= 0) {
                target = scan(w, p);
                lastPos = p.getPos();
                if (target == null) scanCooldown = 10;
            }
            if (target == null) {
                msg(client, zoneMode ? "AutoMine: brak widocznych bloków w strefie w pobliżu..."
                        : "AutoMine: brak widocznych wybranych bloków w pobliżu...");
                return;
            }
        }

        Vec3d eye = p.getEyePos();
        Direction face = exposedFace(w, target, eye);
        if (face == null) {
            target = null;
            return;
        }
        Vec3d center = Vec3d.ofCenter(target);
        Vec3d faceCenter = center.add(face.getOffsetX() * 0.5, face.getOffsetY() * 0.5, face.getOffsetZ() * 0.5);
        double dist = eye.distanceTo(faceCenter);
        lookAt(p, eye, center);

        boolean mining = false;
        if (dist <= REACH) {
            BlockHitResult hit = w.raycast(new RaycastContext(eye, center,
                    RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, p));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target)) {
                selectBestTool(p, w.getBlockState(target));
                // attackBlock omija 5-tickową przerwę między blokami, więc przy szybkim narzędziu
                // kopiemy do 1 bloku na tick
                if (!target.equals(lastStarted)) {
                    client.interactionManager.attackBlock(target, hit.getSide());
                    lastStarted = target;
                }
                client.interactionManager.updateBlockBreakingProgress(target, hit.getSide());
                p.swingHand(Hand.MAIN_HAND);
                mining = true;
                miningTicks++;
                stuckTicks = 0;
            }
        }

        if (!mining) {
            miningTicks = 0;
            boolean jump = p.horizontalCollision || p.isTouchingWater();
            setKeys(client, true, jump);
            if (++stuckTicks >= 60) {
                if (p.getPos().distanceTo(lastPos) < 0.6) {
                    blacklist(w, target);
                    target = null;
                    lastStarted = null;
                }
                lastPos = p.getPos();
                stuckTicks = 0;
            }
        } else if (miningTicks > 400) { // 20 s na jednym bloku = coś jest nie tak
            blacklist(w, target);
            target = null;
            lastStarted = null;
        }
    }

    // ---------- zones ----------
    private static BlockPos lookedAtPos(MinecraftClient client) {
        if (client.crosshairTarget instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            return hit.getBlockPos();
        }
        return null;
    }

    private static void handleZoneKey(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        boolean ctrl = isDown(client, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_RIGHT_CONTROL);
        boolean shift = isDown(client, GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT);

        if (shift) {
            zones.clear();
            pendingCorner = null;
            saveZones();
            target = null;
            msg(client, "AutoMine: usunięto wszystkie strefy");
            return;
        }
        if (ctrl) {
            if (zones.isEmpty()) {
                msg(client, "AutoMine: nie ma żadnych stref");
            } else {
                zones.remove(zones.size() - 1);
                saveZones();
                target = null;
                msg(client, "AutoMine: usunięto ostatnią strefę (zostało " + zones.size() + ")");
            }
            return;
        }

        BlockPos pos = lookedAtPos(client);
        if (pos == null) {
            msg(client, "AutoMine: patrz na blok, który ma być rogiem strefy");
            return;
        }
        if (pendingCorner == null || !curDim.equals(pendingDim)) {
            pendingCorner = pos;
            pendingDim = curDim;
            msg(client, "AutoMine: róg 1 = " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                    + " - patrz na przeciwległy róg i naciśnij Z");
            return;
        }
        BlockPos a = pendingCorner;
        Zone z = new Zone(curDim,
                Math.min(a.getX(), pos.getX()), Math.min(a.getY(), pos.getY()), Math.min(a.getZ(), pos.getZ()),
                Math.max(a.getX(), pos.getX()), Math.max(a.getY(), pos.getY()), Math.max(a.getZ(), pos.getZ()));
        zones.add(z);
        pendingCorner = null;
        zoneMode = true;
        saveZones();
        target = null;
        msg(client, "AutoMine: strefa " + zones.size() + " dodana ("
                + (z.x2() - z.x1() + 1) + "x" + (z.y2() - z.y1() + 1) + "x" + (z.z2() - z.z1() + 1)
                + "), tryb STREFY włączony");
    }

    private static boolean inAnyZone(BlockPos pos) {
        for (Zone z : zones) {
            if (z.dim().equals(curDim) && z.contains(pos)) return true;
        }
        return false;
    }

    private static boolean hasZoneHere() {
        for (Zone z : zones) {
            if (z.dim().equals(curDim)) return true;
        }
        return false;
    }

    // ---------- start / stop ----------
    private static void addLookedAtBlock(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        BlockPos pos = lookedAtPos(client);
        if (pos == null) {
            msg(client, "AutoMine: patrz na blok, który chcesz dodać");
            return;
        }
        Block block = client.world.getBlockState(pos).getBlock();
        if (TARGETS.add(block)) {
            save();
            client.player.sendMessage(Text.literal("AutoMine: dodano ").append(block.getName()), true);
        } else {
            client.player.sendMessage(Text.literal("AutoMine: ").append(block.getName())
                    .append(Text.literal(" jest już na liście")), true);
        }
    }

    private static void start(MinecraftClient client) {
        if (client.player == null) return;
        if (zoneMode && !hasZoneHere()) {
            msg(client, "AutoMine: najpierw zaznacz strefę (klawisz Z) albo przełącz tryb (N)");
            return;
        }
        if (!zoneMode && TARGETS.isEmpty()) {
            msg(client, "AutoMine: najpierw wybierz bloki (M lub Ctrl+K)");
            return;
        }
        client.options.pauseOnLostFocus = false; // żeby gra działała w tle
        active = true;
        target = null;
        lastStarted = null;
        scanCooldown = 0;
        blacklist.clear();
        msg(client, zoneMode ? "AutoMine: włączony (strefy)" : "AutoMine: włączony (bloki)");
    }

    private static void stop(MinecraftClient client, String text) {
        active = false;
        target = null;
        lastStarted = null;
        setKeys(client, false, false);
        msg(client, text);
    }

    private static void setKeys(MinecraftClient c, boolean forward, boolean jump) {
        c.options.forwardKey.setPressed(forward);
        c.options.sprintKey.setPressed(forward);
        c.options.jumpKey.setPressed(jump);
    }

    private static void blacklist(ClientWorld w, BlockPos pos) {
        blacklist.put(pos.toImmutable(), w.getTime() + 20 * 60);
    }

    // ---------- finding blocks ----------
    private static boolean matches(ClientWorld w, BlockPos pos) {
        BlockState st = w.getBlockState(pos);
        if (zoneMode) {
            if (st.isAir() || !st.getFluidState().isEmpty()) return false;
            if (st.getHardness(w, pos) < 0) return false; // bedrock itp.
            return inAnyZone(pos);
        }
        return TARGETS.contains(st.getBlock());
    }

    private static void consider(ClientWorld w, BlockPos.Mutable m, Vec3d eye) {
        if (!matches(w, m)) return;
        double d = Vec3d.ofCenter(m).squaredDistanceTo(eye);
        if (d >= scanBestD) return;
        if (blacklist.containsKey(m)) return;
        if (exposedFace(w, m, eye) == null) return;
        scanBestD = d;
        scanBest = m.toImmutable();
    }

    private static BlockPos scan(ClientWorld w, ClientPlayerEntity p) {
        Vec3d eye = p.getEyePos();
        BlockPos o = p.getBlockPos();
        long now = w.getTime();
        blacklist.values().removeIf(t -> t < now);
        scanBest = null;
        scanBestD = Double.MAX_VALUE;
        BlockPos.Mutable m = new BlockPos.Mutable();
        int r = SCAN_RADIUS;

        if (zoneMode) {
            for (Zone z : zones) {
                if (!z.dim().equals(curDim)) continue;
                int x0 = Math.max(z.x1(), o.getX() - r), x1 = Math.min(z.x2(), o.getX() + r);
                int y0 = Math.max(z.y1(), o.getY() - r), y1 = Math.min(z.y2(), o.getY() + r);
                int z0 = Math.max(z.z1(), o.getZ() - r), z1 = Math.min(z.z2(), o.getZ() + r);
                for (int x = x0; x <= x1; x++)
                    for (int y = y0; y <= y1; y++)
                        for (int zz = z0; zz <= z1; zz++) {
                            m.set(x, y, zz);
                            consider(w, m, eye);
                        }
            }
        } else {
            for (int x = -r; x <= r; x++)
                for (int y = -r; y <= r; y++)
                    for (int z = -r; z <= r; z++) {
                        m.set(o.getX() + x, o.getY() + y, o.getZ() + z);
                        consider(w, m, eye);
                    }
        }
        return scanBest;
    }

    private static boolean isValid(ClientWorld w, BlockPos pos, Vec3d eye) {
        return matches(w, pos) && exposedFace(w, pos, eye) != null;
    }

    /** Ściana bloku stykająca się z otwartą przestrzenią (blok jest widoczny), najbliższa oczom gracza. */
    private static Direction exposedFace(ClientWorld w, BlockPos pos, Vec3d eye) {
        Direction best = null;
        double bestD = Double.MAX_VALUE;
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            BlockState ns = w.getBlockState(n);
            if (ns.isSideSolidFullSquare(w, n, d.getOpposite())) continue;
            Vec3d fc = Vec3d.ofCenter(pos).add(d.getOffsetX() * 0.5, d.getOffsetY() * 0.5, d.getOffsetZ() * 0.5);
            double dist = fc.squaredDistanceTo(eye);
            if (dist < bestD) {
                bestD = dist;
                best = d;
            }
        }
        return best;
    }

    // ---------- helpers ----------
    private static void lookAt(ClientPlayerEntity p, Vec3d eye, Vec3d t) {
        double dx = t.x - eye.x, dy = t.y - eye.y, dz = t.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        p.setYaw((float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f);
        p.setPitch(MathHelper.clamp((float) -(MathHelper.atan2(dy, h) * 180.0 / Math.PI), -90f, 90f));
    }

    private static void selectBestTool(ClientPlayerEntity p, BlockState state) {
        PlayerInventory inv = p.getInventory();
        int best = inv.selectedSlot;
        float bestSpeed = inv.getStack(best).getMiningSpeedMultiplier(state);
        for (int i = 0; i < 9; i++) {
            float s = inv.getStack(i).getMiningSpeedMultiplier(state);
            if (s > bestSpeed) {
                bestSpeed = s;
                best = i;
            }
        }
        inv.selectedSlot = best;
    }
}
