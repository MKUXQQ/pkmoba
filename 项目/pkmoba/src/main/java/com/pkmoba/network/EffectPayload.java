package com.pkmoba.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * S2C：播放一个 Cobblemon 招式粒子特效。
 *
 * <p>为什么由服务端发 id、客户端去查表生成：
 * <ul>
 *   <li>{@code ParticleStorm} / {@code BedrockParticleOptionsRepository} 都是客户端类，
 *       服务端拿不到；</li>
 *   <li>把特效交给客户端生成，就不必依赖 Cube/粒子在服务端的可用性。</li>
 * </ul>
 * effectId 例如 {@code cobblemon:flamethrower_actor}。
 *
 * <p>0.39（task-15）：同一个包也承载「命中反馈」事件。为了<b>不新增协议字段、不改字段顺序</b>，
 * 命中事件把结构化数据编进 {@link #effectId()}：
 * <pre>pkmoba:hit/&lt;fire|water|physical&gt;/&lt;0|1|2&gt;[/d&lt;伤害&gt;][/e&lt;目标实体id&gt;][/kill]</pre>
 * <ul>
 *   <li>{@code x/y/z} = <b>真实命中点</b>（{@code CombatDamage.apply} 里伤害真正被接受的时刻才算）；</li>
 *   <li>{@code dx/dy/dz} = 命中方向（攻击者 → 目标，已归一化，可能为零）；</li>
 *   <li>第二段 {@code 0/1/2} = 轻击 / 重击 / 大招；末尾 {@code kill} = 这一下真的把目标打死了
 *       （客户端只用来显示斩杀提示，<b>不参与任何伤害计算</b>）。</li>
 * </ul>
 *
 * <p>为什么复用而不是新开包：新增 S2C 包要改网络注册与 {@code PkmobaClient} 的接收器，
 * 而命中反馈的生命周期与本包完全一致（一次性、带坐标）；字符串前缀足够表达枚举，
 * 旧版本客户端收到后只会查不到粒子 id 而安全忽略。
 */
public record EffectPayload(String effectId, double x, double y, double z,
                            double dx, double dy, double dz) implements CustomPacketPayload {
    public static final Type<EffectPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("pkmoba", "effect"));

    public static final StreamCodec<RegistryFriendlyByteBuf, EffectPayload> CODEC =
            StreamCodec.of((buf, p) -> {
                        buf.writeUtf(p.effectId());
                        buf.writeDouble(p.x());
                        buf.writeDouble(p.y());
                        buf.writeDouble(p.z());
                        buf.writeDouble(p.dx());
                        buf.writeDouble(p.dy());
                        buf.writeDouble(p.dz());
                    },
                    buf -> new EffectPayload(buf.readUtf(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                            buf.readDouble(), buf.readDouble(), buf.readDouble()));

    /** 命中反馈事件的 effectId 前缀。 */
    public static final String HIT_PREFIX = "pkmoba:hit/";

    /** 命中元素：决定粒子配色、屏闪颜色与命中音。 */
    public enum Element {
        FIRE("fire"),
        WATER("water"),
        PHYSICAL("physical");

        private final String id;

        Element(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        static Element byId(String raw) {
            for (Element element : values()) {
                if (element.id.equals(raw)) {
                    return element;
                }
            }
            return null;
        }
    }

    /**
     * 打击强度：客户端据此决定震屏幅度 / 顿帧 tick 数 / 闪白时长；
     * 服务端只按真实伤害量选档，<b>不参与伤害计算</b>。
     */
    public enum Power {
        /** 轻击：顿帧 3 tick、震屏 0.62 格、闪白 2 tick。 */
        LIGHT("0", 3, 0.62F, 2),
        /** 重击：顿帧 4 tick、震屏 1.00 格、闪白 3 tick。 */
        HEAVY("1", 4, 1.00F, 3),
        /** 大招：顿帧 6 tick、震屏 1.45 格、闪白 5 tick（上限，别糊住画面）。 */
        ULTIMATE("2", 6, 1.45F, 5);

        /**
         * 更轻一档的强度（0.39）。
         *
         * <p>用于"自己打中目标"时的客户端手感：保留顿帧，但不需要和挨打一样的重顿。
         * LIGHT 仍返回 LIGHT（已经最轻），HEAVY→LIGHT，ULTIMATE→HEAVY。
         */
        public Power weaker() {
            return switch (this) {
                case LIGHT -> LIGHT;
                case HEAVY -> LIGHT;
                case ULTIMATE -> HEAVY;
            };
        }

        private final String id;
        private final int hitstopTicks;
        private final float shakeAmplitude;
        private final int flashTicks;

        Power(String id, int hitstopTicks, float shakeAmplitude, int flashTicks) {
            this.id = id;
            this.hitstopTicks = hitstopTicks;
            this.shakeAmplitude = shakeAmplitude;
            this.flashTicks = flashTicks;
        }

        public String id() {
            return id;
        }

        /** 建议的局部顿帧 tick 数（客户端自己计时，绝不改 tickrate）。 */
        public int hitstopTicks() {
            return hitstopTicks;
        }

        /** 建议的震屏峰值（单位：格，相机世界空间位移）。 */
        public float shakeAmplitude() {
            return shakeAmplitude;
        }

        /** 建议的屏闪 tick 数。 */
        public int flashTicks() {
            return flashTicks;
        }

        static Power byId(String raw) {
            for (Power power : values()) {
                if (power.id.equals(raw)) {
                    return power;
                }
            }
            return null;
        }
    }

    /** 服务端构造一条命中反馈包；point/dir 允许为 null（会安全降级）。不带伤害数值。 */
    public static EffectPayload hit(Element element, Power power, boolean lethal, Vec3 point, Vec3 dir) {
        return hit(element, power, lethal, point, dir, 0.0F, -1);
    }

    /**
     * 服务端构造一条命中反馈包，附带真实伤害数值与目标实体 id（0.40 task-20 追加）。
     *
     * <p>为什么要这两个数：客户端伤害数字必须显示<b>真实</b>伤害，并且同一目标 0.15s 内的多段命中
     * 要能精确合并到同一个数字上（靠坐标邻近猜测会把邻近的另一个目标混进来）。
     *
     * <p>兼容性：仍然只有 {@code effectId} 一个字符串字段、六个 double，<b>协议帧结构完全没变</b>；
     * 新数据只是追加成 effectId 的可选段，旧格式解析路径照常工作。
     */
    public static EffectPayload hit(Element element, Power power, boolean lethal, Vec3 point, Vec3 dir,
                                   float damage, int targetId) {
        Element el = element == null ? Element.PHYSICAL : element;
        Power pw = power == null ? Power.LIGHT : power;
        Vec3 p = point == null ? Vec3.ZERO : point;
        Vec3 d = dir != null && dir.lengthSqr() > 1.0E-8 ? dir.normalize() : Vec3.ZERO;
        StringBuilder id = new StringBuilder(HIT_PREFIX)
                .append(el.id()).append('/').append(pw.id());
        if (Float.isFinite(damage) && damage > 0.0F) {
            // 保留一位小数：数字显示只用一位，少一点字符长度。
            id.append("/d").append(Math.round(damage * 10.0F) / 10.0F);
        }
        if (targetId >= 0) {
            id.append("/e").append(targetId);
        }
        if (lethal) {
            id.append("/kill");
        }
        return new EffectPayload(id.toString(), p.x, p.y, p.z, d.x, d.y, d.z);
    }

    /** 是不是命中反馈事件（而不是要给 Cobblemon 粒子仓库查表的 effectId）。 */
    public boolean isHit() {
        return hitElement() != null;
    }

    /** 命中元素；不是命中事件时返回 null。 */
    public Element hitElement() {
        String[] parts = hitParts();
        return parts != null && parts.length >= 2 ? Element.byId(parts[0]) : null;
    }

    /** 命中强度；解析失败按轻击处理（宁可轻一点，也不要吓人）。 */
    public Power hitPower() {
        String[] parts = hitParts();
        if (parts == null || parts.length < 2) {
            return Power.LIGHT;
        }
        Power power = Power.byId(parts[1]);
        return power == null ? Power.LIGHT : power;
    }

    /** 这一下是否真的打死目标（服务端真实 HP 判定，客户端只读）。 */
    public boolean hitLethal() {
        String[] parts = hitParts();
        if (parts == null) {
            return false;
        }
        for (int i = 2; i < parts.length; i++) {
            if ("kill".equals(parts[i])) {
                return true;
            }
        }
        return false;
    }

    /** 真实伤害数值（服务端实际扣掉的血量）；旧格式或非法值返回 0。 */
    public float hitDamage() {
        String[] parts = hitParts();
        if (parts == null) {
            return 0.0F;
        }
        for (int i = 2; i < parts.length; i++) {
            if (parts[i].startsWith("d") && parts[i].length() > 1) {
                try {
                    float value = Float.parseFloat(parts[i].substring(1));
                    return Float.isFinite(value) && value > 0.0F ? value : 0.0F;
                } catch (NumberFormatException ignored) {
                    return 0.0F;
                }
            }
        }
        return 0.0F;
    }

    /** 被打中的目标实体 id；旧格式或非法值返回 -1（客户端退回按坐标合并）。 */
    public int hitTargetId() {
        String[] parts = hitParts();
        if (parts == null) {
            return -1;
        }
        for (int i = 2; i < parts.length; i++) {
            if (parts[i].startsWith("e") && parts[i].length() > 1) {
                try {
                    return Integer.parseInt(parts[i].substring(1));
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    /** 真实命中点。 */
    public Vec3 hitPoint() {
        return new Vec3(x, y, z);
    }

    /** 命中方向（攻击者 → 目标，单位向量或零向量）。 */
    public Vec3 hitDirection() {
        Vec3 d = new Vec3(dx, dy, dz);
        return d.lengthSqr() > 1.0E-8 ? d.normalize() : Vec3.ZERO;
    }

    private String[] hitParts() {
        if (effectId == null || !effectId.startsWith(HIT_PREFIX)) {
            return null;
        }
        String rest = effectId.substring(HIT_PREFIX.length());
        return rest.isEmpty() ? null : rest.split("/");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
