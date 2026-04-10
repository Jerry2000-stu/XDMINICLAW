package com.xd.xdminiclaw.bot;

/**
 * 线程级用户上下文持有者
 * 在 MessageProcessorService 处理每条消息前设置，处理完毕后清除。
 * QQFileSenderTool 通过此类获取当前用户的 openId 和 msgId，以便上传并发送媒体文件。
 */
public class UserContextHolder {

    public record UserContext(String openId, String msgId) {}

    private static final ThreadLocal<UserContext> HOLDER = new ThreadLocal<>();

    public static void set(UserContext ctx)  { HOLDER.set(ctx); }
    public static UserContext get()          { return HOLDER.get(); }
    public static void clear()               { HOLDER.remove(); }
}