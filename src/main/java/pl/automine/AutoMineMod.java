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

    private static final int SCAN_RADIUS = 20;
    private static final double REACH = 4.2;

    private static KeyBinding openKey;
    private static KeyBinding toggleKey;

    private static boolean active = false;
    private static BlockPos target = null;
    private static int scanCooldown = 0;
    private static int stuckTicks = 0;
    private static int miningTicks = 0;
    private static Vec3d lastPos = Vec3d.ZERO;
    private static final Map<BlockPos, Long> blacklist = new HashMap<>();

    @Override
    public void onInitializeClient() {
        load();
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.automine.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M, "key.categories.automine"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.automine.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.automine"));
        ClientTickEvents.START_CLIENT_TICK.register(AutoMineMod::tick);
    }

    // ---------- config ----------
    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("automine.txt");
    }

    public static void save() {
        List<String> lines = new ArrayList<>();
        for (Block b : TARGETS) lines.add(Registries.BLOCK.getId(b).toString());
        try {
            Files.write(configPath(), lines);
        } catch (IOException ignored) {
        }
    }

    private static void load() {
        try {
            if (!Files.exists(configPath())) return;
            for (String line : Files.readAllLines(configPath())) {
                Identifier id = Identifier.tryParse(line.trim());
                if (id != null && Registries.BLOCK.containsId(id)) TARGETS.add(Registries.BLOCK.get(id));
            }
        } catch (IOException ignored) {
        }
    }

    // ---------- main loop ----------
    private static void tick(MinecraftClient client) {
        while (openKey.wasPressed()) {
            client.setScreen(new BlockSelectScreen());
        }
        while (toggleKey.wasPressed()) {
            long window = client.getWindow().getHandle();
            boolean ctrl = InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_LEFT_CONTROL)
                    || InputUtil.isKeyPressed(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
            if (ctrl) {
                addLookedAtBlock(client);
            } else if (active) {
                stop(client, "AutoMine: wyłączony");
            } else {
                start(client);
            }
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
        }
        if (target == null) {
            stuckTicks = 0;
            miningTicks = 0;
            if (--scanCooldown <= 0) {
                scanCooldown = 10;
                target = scan(w, p);
                lastPos = p.getPos();
            }
            if (target == null) {
                p.sendMessage(Text.literal("AutoMine: brak widocznych wybranych bloków w pobliżu..."), true);
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
                }
                lastPos = p.getPos();
                stuckTicks = 0;
            }
        } else if (miningTicks > 400) { // 20 s na jednym bloku = coś jest nie tak
            blacklist(w, target);
            target = null;
        }
    }

    /** Ctrl+K: dodaje do listy blok, na który patrzy gracz. */
    private static void addLookedAtBlock(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        if (!(client.crosshairTarget instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) {
            client.player.sendMessage(Text.literal("AutoMine: patrz na blok, który chcesz dodać"), true);
            return;
        }
        Block block = client.world.getBlockState(hit.getBlockPos()).getBlock();
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
        if (TARGETS.isEmpty()) {
            client.player.sendMessage(Text.literal("AutoMine: najpierw wybierz bloki (klawisz M)"), true);
            return;
        }
        client.options.pauseOnLostFocus = false; // żeby gra działała w tle
        active = true;
        target = null;
        scanCooldown = 0;
        blacklist.clear();
        client.player.sendMessage(Text.literal("AutoMine: włączony"), true);
    }

    private static void stop(MinecraftClient client, String msg) {
        active = false;
        target = null;
        setKeys(client, false, false);
        if (client.player != null) client.player.sendMessage(Text.literal(msg), true);
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
    private static BlockPos scan(ClientWorld w, ClientPlayerEntity p) {
        Vec3d eye = p.getEyePos();
        BlockPos origin = p.getBlockPos();
        long now = w.getTime();
        blacklist.values().removeIf(t -> t < now);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = -SCAN_RADIUS; x <= SCAN_RADIUS; x++)
            for (int y = -SCAN_RADIUS; y <= SCAN_RADIUS; y++)
                for (int z = -SCAN_RADIUS; z <= SCAN_RADIUS; z++) {
                    m.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (!TARGETS.contains(w.getBlockState(m).getBlock())) continue;
                    double d = Vec3d.ofCenter(m).squaredDistanceTo(eye);
                    if (d >= bestD) continue;
                    if (blacklist.containsKey(m)) continue;
                    if (exposedFace(w, m, eye) == null) continue;
                    bestD = d;
                    best = m.toImmutable();
                }
        return best;
    }

    private static boolean isValid(ClientWorld w, BlockPos pos, Vec3d eye) {
        return TARGETS.contains(w.getBlockState(pos).getBlock()) && exposedFace(w, pos, eye) != null;
    }

    /** Zwraca ścianę bloku, która styka się z "otwartą" przestrzenią (blok jest widoczny), najbliższą oczom gracza. */
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
