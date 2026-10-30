package com.pkmoba.combat;

import com.cobblemon.mod.common.entity.pokemon.PokemonEntity;
import java.util.Map;
import net.minecraft.world.phys.Vec3;

/**
 * 嘴部锚点的唯一数据源（服务端弹道原点 + 客户端特效原点共用）。
 *
 * <p>偏移量来自 Cobblemon 自带的骨骼定位器，从模型 geo 文件里直接量出来的
 * （bedrock 单位 16 = 1 格，模型 -Z 为身体正前方）：
 * <ul>
 *   <li>喷火龙 {@code locator_mouth} = [0, 45.75, -2] → 向上 2.859 格、向前 0.125 格</li>
 *   <li>水箭龟 {@code locator_mouth} = [0, 41.0, -12.75] → 向上 2.5625 格、向前 0.797 格</li>
 * </ul>
 *
 * <p>为什么不再用 {@code getEyePosition()}：那是碰撞箱眼睛高度（喷火龙约 2.2 格），
 * 而真嘴在 2.86 格、还比眼睛靠前，用眼睛当嘴就会看到"火焰从胸口/下巴飘出来"。
 *
 * <p>客户端能拿到活体骨骼定位器（{@code client.MouthLocator}）时以活体为准，
 * 本类只在服务端 / 拿不到定位器时兜底。
 */
public final class MouthAnchor {

    /**
     * 嘴部偏移。
     *
     * @param forward 沿身体正前方的水平偏移（格）
     * @param up      相对脚底的高度（格）
     * @param side    向右的侧向偏移（格，喷火龙/水箭龟均为 0）
     */
    public record Offset(double forward, double up, double side) {
    }

    /** 喷火龙：locator_mouth [0, 45.75, -2]。 */
    public static final Offset CHARIZARD = new Offset(0.125, 2.859, 0.0);
    /** 水箭龟：locator_mouth [0, 41, -12.75]。 */
    public static final Offset BLASTOISE = new Offset(0.797, 2.563, 0.0);
    /**
     * 水箭龟背部左炮口（模型自身左侧，即 {@link Offset#side()} 为负）。
     *
     * <p>来源：geo {@code locator_cannon_left} 的 locator 坐标 [15, 41, -12]（bedrock 16 = 1 格）。
     * 它挂在 {@code cannon_left → shell_fold_left → shell → torso → body2 → body → blastoise} 骨骼链上，
     * 祖先 pivot 与旋转如下（模型空间绝对坐标，Z-45° 折壳用来把炮管抬起）：
     * <pre>
     * torso[0,14,0] → shell[-4,21,10] rot[10,0,0]
     *   → shell_fold_left[9.5,45,17.5] rot[0,0,45]
     *   → shell_cannon_left[9.5,36,17.5] → locator_cannon_left[15,41,-12]
     * </pre>
     * 按"pivot 是模型空间绝对坐标、每级旋转绕自身 pivot"展开并除以 16 得到静止姿态偏移：
     * 前 0.54 格、上 2.43 格、侧 0.66 格（与旧客户端兜底 eye+0.3/±0.65 量级一致）。
     *
     * <p>注意：动画（shell 开合、缩壳、旋转）会让真实炮口偏离这个静止值，
     * 所以服务端只把它当"同源的确定性起点"，客户端优先用活体定位器（见 client/WaterEffects）。
     */
    public static final Offset BLASTOISE_CANNON_LEFT = new Offset(0.54, 2.43, -0.66);
    /** 水箭龟背部右炮口，镜像自 {@link #BLASTOISE_CANNON_LEFT}（locator_cannon_right [−15, 41, −12]）。 */
    public static final Offset BLASTOISE_CANNON_RIGHT = new Offset(0.54, 2.43, 0.66);
    /** 未知物种兜底：按碰撞箱高度 85% 估算，仍然比眼睛略高。 */
    public static final Offset FALLBACK = new Offset(0.35, 2.0, 0.0);

    private static final Map<String, Offset> BY_SPECIES = Map.of(
            "cobblemon:charizard", CHARIZARD,
            "cobblemon:blastoise", BLASTOISE);

    private MouthAnchor() {
    }

    /** 物种 id（带 {@code cobblemon:} 前缀）。 */
    public static String speciesOf(PokemonEntity entity) {
        if (entity == null || entity.getPokemon() == null) {
            return "";
        }
        try {
            return entity.getPokemon().getSpecies().getResourceIdentifier().toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    public static Offset offsetOf(PokemonEntity entity) {
        Offset offset = BY_SPECIES.get(speciesOf(entity));
        if (offset != null) {
            return offset;
        }
        if (entity == null) {
            return FALLBACK;
        }
        double height = Math.max(0.6, entity.getBbHeight());
        return new Offset(0.3, height * 0.85, 0.0);
    }

    /** 由偏航角得到水平正前方单位向量（与 {@code entity.getYRot()} 一致）。 */
    public static Vec3 forward(float yRot) {
        double rad = Math.toRadians(yRot);
        return new Vec3(-Math.sin(rad), 0.0, Math.cos(rad));
    }

    /**
     * 服务端/兜底用的嘴部世界坐标：脚底 + 向上偏移 + 沿当前朝向的前向偏移。
     *
     * <p>{@code extraForward} 是调用方额外前推的距离（例如喷火出口想再离开嘴一点点）。
     */
    public static Vec3 position(PokemonEntity entity, double extraForward) {
        if (entity == null) {
            return Vec3.ZERO;
        }
        Offset offset = offsetOf(entity);
        Vec3 forward = forward(entity.getYRot());
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x);
        return entity.position()
                .add(forward.scale(offset.forward() + extraForward))
                .add(right.scale(offset.side()))
                .add(0.0, offset.up(), 0.0);
    }

    /**
     * 通用版本：按指定偏移算世界坐标（服务端拿不到活体定位器时用静止姿态兜底）。
     */
    public static Vec3 position(PokemonEntity entity, Offset offset, double extraForward) {
        if (entity == null) {
            return Vec3.ZERO;
        }
        Vec3 forward = forward(entity.getYRot());
        Vec3 right = new Vec3(-forward.z, 0.0, forward.x);
        return entity.position()
                .add(forward.scale(offset.forward() + extraForward))
                .add(right.scale(offset.side()))
                .add(0.0, offset.up(), 0.0);
    }

    /**
     * 水箭龟背部炮口世界坐标（加农水炮的束流起点）。
     *
     * <p>服务端和客户端必须用同一套"左右"语义：{@code left == true} 取
     * {@link #BLASTOISE_CANNON_LEFT}（模型自身左侧，side 为负），否则取右炮口。
     * 非水箭龟物种回退到嘴部偏移 {@link #position(PokemonEntity, double)}，避免其它宝可梦
     * 误用两门炮。炮口方向由调用方决定（本方法只负责位置，不碰朝向）。
     */
    public static Vec3 cannonPosition(PokemonEntity entity, boolean left) {
        return cannonPosition(entity, left, 0.0);
    }

    /** 同 {@link #cannonPosition(PokemonEntity, boolean)}，可沿身体正前方额外前推。 */
    public static Vec3 cannonPosition(PokemonEntity entity, boolean left, double extraForward) {
        if (entity == null) {
            return Vec3.ZERO;
        }
        if (!"cobblemon:blastoise".equals(speciesOf(entity))) {
            return position(entity, extraForward);
        }
        Offset offset = left ? BLASTOISE_CANNON_LEFT : BLASTOISE_CANNON_RIGHT;
        return position(entity, offset, extraForward);
    }
}
