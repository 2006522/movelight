package dev.movelight;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * 移动光源的核心：算出玩家手上能发多少光，然后在身边放一块隐形光方块。
 *
 * <p>每 tick 做三件事：</p>
 * <ol>
 *   <li>看主手/副手拿着什么，算出亮度等级 0~15</li>
 *   <li>算出光源该放在哪一格（默认脚下那一格，可配偏移）</li>
 *   <li>位置或亮度变了，就把旧的那块撤掉、在新位置放一块</li>
 * </ol>
 *
 * <p><b>复用原版的 {@code minecraft:light} 方块</b>，不自己新建方块。理由：</p>
 * <ul>
 *   <li>它的渲染形状就是 {@code INVISIBLE}，碰撞箱是空的 —— 玩家看不见也摸不到，
 *       连光线追踪都不会命中它，所以不会干扰挖方块/放方块</li>
 *   <li>它自带 {@code level} 属性（0~15）直接决定亮度，正是我们要的</li>
 *   <li>不挡天光，方块光能正常向外扩散</li>
 *   <li><b>不需要在运行时注册方块</b>。这是关键：{@code BuiltInRegistries.BLOCK} 在
 *       模组初始化阶段就已经冻结，此时再 register 会直接抛 {@code IllegalStateException}，
 *       用现成方块把整块风险绕开了</li>
 * </ul>
 *
 * <p><b>为什么换位置是"撤掉旧的再放新的"而不是"原地改 level"</b>：光只会从方块所在格
 * 向外扩散，原地改 level 不会挪动光源，所以位置变了必须整体搬走。同一格只改亮度时
 * 才直接改状态，省一次位置搬移。</p>
 */
public final class MovingLight {

    /** 物品 → 亮度。手上东西不常变，缓存一下省得每 tick 查注册表。 */
    private static final Map<Item, Integer> LIGHT_CACHE = new HashMap<>();

    /** 方块 → 它的最高亮度。红石灯这类要遍历全部状态才知道，缓存起来。 */
    private static final Map<Block, Integer> MAX_LIGHT_CACHE = new HashMap<>();

    private MovingLight() {
    }

    /** 手上物品对应的亮度 0~15；0 表示这东西不发光。 */
    static int lightOf(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        return LIGHT_CACHE.computeIfAbsent(stack.getItem(), MovingLight::resolveLight);
    }

    private static int resolveLight(Item item) {
        // 手持的方块：跟它"亮着的时候"一样亮。
        // 这里不能只看 defaultBlockState() —— 红石灯默认是灭的、蜡烛默认没点燃，
        // 取默认状态会把它们算成 0。所以要遍历所有状态取最大值。
        if (item instanceof BlockItem blockItem) {
            int emission = maxLightOf(blockItem.getBlock());
            if (emission > 0) {
                return emission;
            }
        }
        // 不是方块但也在发光映射表里的（岩浆桶、萤石粉……）
        return Config.lightOfItem(item);
    }

    /**
     * 方块所有可能状态里的最高亮度。
     *
     * <p>结果按方块缓存 —— 一个方块的状态最多几千个，但同一物品每 tick 都会问到，
     * 缓存下来避免重复遍历。</p>
     */
    private static int maxLightOf(Block block) {
        return MAX_LIGHT_CACHE.computeIfAbsent(block, b -> {
            int max = 0;
            // getPossibleStates() 返回 Guava 的 ImmutableList，直接 for-each 会让 javac
            // 去加载那个类。这里退成 Iterable 接口，编译期就不需要 Guava 了。
            Iterable<BlockState> states = b.getStateDefinition().getPossibleStates();
            for (BlockState state : states) {
                max = Math.max(max, state.getLightEmission());
            }
            return max;
        });
    }

    /** 当前该用的亮度（取主副手较大的那个，再按配置乘倍率）。 */
    static int currentLight(Player player) {
        Config config = Config.get();
        if (!config.isEnable()) {
            return 0;
        }
        int main = lightOf(player.getMainHandItem());
        int off = lightOf(player.getOffhandItem());
        int light = Math.max(main, off);
        if (light <= 0) {
            return 0;
        }
        int scaled = (int) Math.round(light * config.getBrightnessScale());
        return Math.max(0, Math.min(LightBlock.MAX_LEVEL, scaled));
    }

    // ---------------------------------------------------------------- 放置状态

    /** 现在还亮着的那一格；null 表示当前没有光。 */
    private static BlockPos placedPos;
    private static int placedLevel;

    /** 当前世界；世界换了（换维度/退存档）就得把记录清掉。 */
    private static ClientLevel placedLevelRef;

    /**
     * 更新标志。
     *
     * <p>{@code UPDATE_CLIENTS}（2）让客户端把这一格标记重绘、并触发一次光照重算
     * （{@code LevelChunk.setBlockState} 里会比对 {@code hasDifferentLightProperties}，
     * 不同就 {@code getLightEngine().checkBlock(pos)}）；</p>
     *
     * <p>{@code UPDATE_KNOWN_SHAPE}（16）告诉引擎"形状没变、不用通知邻居"，
     * 省掉一圈无意义的邻居更新。注意 26.x 里它是 16，不是老版本的 32
     * （32 在这个版本是 {@code UPDATE_SUPPRESS_DROPS}），所以这里一律用常量名，
     * 不写死数字。</p>
     *
     * <p>这是纯客户端操作，不会发包给服务端，所以 flag 只影响本地世界。</p>
     */
    private static final int UPDATE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** 主循环：每个客户端 tick 调一次。 */
    static void tick(Minecraft client) {
        ClientLevel level = client.level;
        if (level == null || client.player == null) {
            forget();
            return;
        }
        if (level != placedLevelRef) {
            // 换了世界，旧位置已经没意义了（旧 level 对象也废了，不能再 setBlock）
            forget();
            placedLevelRef = level;
        }

        int want = currentLight(client.player);
        if (want <= 0) {
            remove(level);
            return;
        }

        BlockPos target = targetPos(level, client.player);
        if (target == null) {
            // 身边找不到能放的空格（比如整个人埋进方块里），把旧的撤掉免得留在原地
            remove(level);
            return;
        }

        if (target.equals(placedPos)) {
            // 同一格：亮度变了要改，方块被别人顶掉了也要补回来。
            //
            // 【必须核对真实方块状态，不能只信 placedPos/placedLevel】
            // 最典型的翻车场景：玩家站在地上挖掉脚下方块 → 光源跟着落进新挖的坑，
            // 而服务端那份「这里是空气」的 ClientboundBlockUpdatePacket 随后才到，
            // 走 ClientLevel.setServerVerifiedBlockState 把那一格强改回空气，光源就没了。
            // 此时位置没变、亮度也没变，只看 want != placedLevel 就会认为无事可做，
            // 光便永远不亮 —— 只有等玩家跳一下/走一格改变了 target 才会重放。
            if (want != placedLevel || !hasLightAt(level, target, want)) {
                setLevel(level, target, want);
            }
            return;
        }
        // 换位置：先撤旧的再放新的，避免同时亮着两盏
        remove(level);
        place(level, target, want);
    }

    /**
     * 光源该在的格子。
     *
     * <p>从玩家脚下那格开始，按「本格 → 上一格 → 下一格 → 上两格」的顺序找位置。
     * 分两轮：</p>
     * <ul>
     *   <li>第一轮只接受<b>纯空气</b>格 —— 站在平地上就是这种情况</li>
     *   <li>第二轮才允许<b>可替换方块</b>（草丛、雪层、藤蔓、火把花…）</li>
     * </ul>
     *
     * <p><b>为什么要把两轮分开</b>：直接接受可替换方块会在玩家站进草丛时把那撮草
     * "挖掉"（客户端世界里被我们替换成隐形的光方块），看起来就是草凭空空了一格。
     * 优先找空气、空气实在没有才动可替换方块，这个副作用就基本见不到了。</p>
     *
     * <p>水里/岩浆里一律不放：把方块塞进流体里会在客户端挖出一个"空洞"
     * （流体渲染跟着方块走），看着比不发光更难受。</p>
     */
    static BlockPos targetPos(ClientLevel level, Player player) {
        Config config = Config.get();
        Vec3 pos = player.position();
        BlockPos origin = BlockPos.containing(
                pos.x + config.getOffsetX(),
                pos.y + config.getOffsetY(),
                pos.z + config.getOffsetZ());

        if (!config.isSnapToGround()) {
            return canPlaceAt(level, origin, true) ? origin : null;
        }
        BlockPos[] candidates = {origin, origin.above(), origin.below(), origin.above(2)};
        for (BlockPos candidate : candidates) {
            if (canPlaceAt(level, candidate, false)) {
                return candidate;
            }
        }
        for (BlockPos candidate : candidates) {
            if (canPlaceAt(level, candidate, true)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 放下（或补回）光源。
     *
     * <p>刻意不看 {@code setBlock} 的返回值来记账：那一格如果本来就已经是同样状态的
     * 光源，{@code Level.setBlock} 会因为「没变化」返回 false（底层
     * {@code LevelChunk.setBlockState} 直接返回 null），照返回值判断就会漏记，
     * 于是下一 tick 又以为要重放。这里改用「放完就记」，真正的正确性由每 tick 的
     * {@link #hasLightAt} 兜底。</p>
     */
    private static void place(ClientLevel level, BlockPos pos, int light) {
        if (!canPlaceAt(level, pos, true)) {
            return;
        }
        level.setBlock(pos, stateOf(light), UPDATE_FLAGS);
        placedPos = pos.immutable();
        placedLevel = light;
    }

    private static void setLevel(ClientLevel level, BlockPos pos, int light) {
        if (!canPlaceAt(level, pos, true)) {
            // 这一格被别人顶掉了，下一 tick 重新找位置
            placedPos = null;
            placedLevel = 0;
            return;
        }
        level.setBlock(pos, stateOf(light), UPDATE_FLAGS);
        placedLevel = light;
    }

    /** 这一格现在确实是我们放的光源、而且亮度正好是 {@code light} 吗。 */
    private static boolean hasLightAt(ClientLevel level, BlockPos pos, int light) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() == Blocks.LIGHT
                && state.getValue(LightBlock.LEVEL) == light;
    }

    private static void remove(ClientLevel level) {
        if (placedPos == null) {
            return;
        }
        BlockPos pos = placedPos;
        placedPos = null;
        placedLevel = 0;
        if (level == null) {
            return;
        }
        // 只撤自己放的：万一那一格已经被别的方块顶替了，别把别人的方块删掉
        if (level.getBlockState(pos).getBlock() == Blocks.LIGHT) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), UPDATE_FLAGS);
        }
    }

    private static void forget() {
        placedPos = null;
        placedLevel = 0;
        placedLevelRef = null;
    }

    /**
     * 这一格能不能放光。
     *
     * @param allowReplace 是否允许占掉可替换方块（草丛之类）。第一轮传 false 只认纯空气。
     */
    private static boolean canPlaceAt(ClientLevel level, BlockPos pos, boolean allowReplace) {
        if (!level.isInValidBounds(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() == Blocks.LIGHT) {
            return true;
        }
        if (state.isAir()) {
            // 空气还要看流体：水源方块本身也报 isAir()，得单独排除
            return level.getFluidState(pos).isEmpty();
        }
        if (!allowReplace || !state.canBeReplaced()) {
            return false;
        }
        return level.getFluidState(pos).isEmpty();
    }

    private static BlockState stateOf(int light) {
        return Blocks.LIGHT.defaultBlockState()
                .setValue(LightBlock.LEVEL, Math.max(0, Math.min(LightBlock.MAX_LEVEL, light)));
    }

    /** 供 HUD / 调试用：当前生效的亮度。 */
    public static int currentLevel() {
        return placedLevel;
    }

    /** 供调试用：当前光源位置。 */
    public static BlockPos currentPos() {
        return placedPos;
    }

    /** 换维度、退出世界时清干净。 */
    public static void reset() {
        forget();
        LIGHT_CACHE.clear();
        MAX_LIGHT_CACHE.clear();
    }

    /** 通知：设置改过了（亮度倍率等），下一 tick 重新计算。 */
    public static void invalidate() {
        LIGHT_CACHE.clear();
        if (placedPos != null) {
            // 打成一个不可能的亮度，强制下一 tick 重设
            placedLevel = -1;
        }
    }
}