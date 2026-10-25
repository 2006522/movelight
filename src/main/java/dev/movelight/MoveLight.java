package dev.movelight;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * 客户端入口。
 *
 * <p>只做两件事：读配置、挂一个 tick 回调。没有 mixin，也不注册任何东西 ——
 * 光方块直接用原版的 {@code minecraft:light}，见 {@link MovingLight} 的说明。</p>
 *
 * <p>整套东西跑在客户端世界里：往 {@code ClientLevel} 临时塞一块隐形光方块、
 * 跟着玩家挪、松开手就撤掉。服务端完全不知道，所以单人存档和联机都能用，
 * 也不会把光方块写进存档（客户端世界根本不会被保存）。</p>
 */
public final class MoveLight implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        Config.get().load();
        Config.get().save();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                MovingLight.tick(client);
            } catch (Exception e) {
                // 单帧出错不要让整个客户端崩掉
                Log.warn("更新移动光源时出错", e);
            }
        });

        Log.info("已加载：手上拿着发光的东西，身边就会亮。");
    }
}