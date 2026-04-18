package org.javaup.lease;

import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料 
 * @description: 管理组件
 * @author: 阿星不是程序员
 **/
/**
 * 基于 Redis Lua 的轻量级租约管理器。
 *
 * <p>ownerToken 表示当前持有租约的任务身份，
 * 只有 token 匹配时才允许续租或释放，避免误删其他实例刚刚接管的租约。</p>
 *
 * <p>它和传统“线程级锁”的关注点不同，建模的是“任务级执行资格”：
 * 1. acquire：尝试占有某个业务 key 的执行资格；
 * 2. renew：在任务仍由自己持有时延长 TTL，维持租约有效；
 * 3. release：在任务结束后按 ownerToken 校验并释放租约。</p>
 *
 * <p>这里选择 Lua 的原因是要把“读 value + 校验 owner + 修改 key”放进 Redis 单条原子命令里，
 * 避免在网络往返之间出现竞争窗口。</p>
 */
public class RedisLeaseManager {

    /*
     * 只有 key 当前不存在时才写入 token 和过期时间。
     *
     * 这相当于一个任务级的 “SET NX PX”，
     * 返回 1 表示当前调用者成功拿到了执行资格，返回 0 表示资格已经被别人占用。
     */
    private static final String ACQUIRE_SCRIPT =
        "if redis.call('exists', KEYS[1]) == 0 then "
            + "redis.call('psetex', KEYS[1], ARGV[2], ARGV[1]); "
            + "return 1; "
            + "end; "
            + "return 0;";

    /*
     * 只有当前 key 的 value 仍然等于 ownerToken 时才允许续期。
     *
     * 这样可以避免旧任务在租约已经过期、并且被新任务接管之后，
     * 还把别人的租约错误续长。
     */
    private static final String RENEW_SCRIPT =
        "if redis.call('get', KEYS[1]) == ARGV[1] then "
            + "redis.call('pexpire', KEYS[1], ARGV[2]); "
            + "return 1; "
            + "end; "
            + "return 0;";

    /*
     * 只有当前 key 的 value 仍然等于 ownerToken 时才允许删除。
     *
     * 这是 ownerToken 最关键的保护作用：
     * 防止旧实例在恢复后，把新实例刚刚接管的租约直接 DEL 掉。
     */
    private static final String RELEASE_SCRIPT =
        "if redis.call('get', KEYS[1]) == ARGV[1] then "
            + "return redis.call('del', KEYS[1]); "
            + "end; "
            + "return 0;";

    private final RedissonClient redissonClient;

    public RedisLeaseManager(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 尝试获取租约。
     *
     * <p>返回 true 表示当前调用者成为这把租约的 owner；
     * 返回 false 表示该业务 key 当前已经被其他任务占用。</p>
     */
    public boolean acquire(String key, String ownerToken, Duration ttl) {
        return executeInteger(ACQUIRE_SCRIPT, key, ownerToken, ttl) == 1L;
    }

    /**
     * 为当前 owner 续租。
     *
     * <p>只有 Redis 中保存的 value 仍然等于 ownerToken 时才会成功。
     * 如果返回 false，说明当前任务已经失去租约，不应继续执行业务写操作。</p>
     */
    public boolean renew(String key, String ownerToken, Duration ttl) {
        return executeInteger(RENEW_SCRIPT, key, ownerToken, ttl) == 1L;
    }

    /**
     * 释放当前 owner 持有的租约。
     *
     * <p>这里不是无条件 DEL，而是 compare-and-delete：
     * 只有 ownerToken 匹配时才删除 key，从而避免误删已经被其他实例接管的新租约。</p>
     */
    public boolean release(String key, String ownerToken) {
        Assert.hasText(key, "key 不能为空");
        Assert.hasText(ownerToken, "ownerToken 不能为空");
        Long result = redissonClient.getScript(StringCodec.INSTANCE).eval(
            RScript.Mode.READ_WRITE,
            RELEASE_SCRIPT,
            RScript.ReturnType.INTEGER,
            List.of(key),
            ownerToken
        );
        return result != null && result == 1L;
    }

    private long executeInteger(String script, String key, String ownerToken, Duration ttl) {
        /*
         * 对外暴露的是语义化的 acquire/renew，底层真正执行则复用一套
         * “单 key + token + ttl -> Lua 整数返回值”的模板。
         */
        Assert.hasText(key, "key 不能为空");
        Assert.hasText(ownerToken, "ownerToken 不能为空");
        Assert.notNull(ttl, "ttl 不能为空");
        Assert.isTrue(!ttl.isNegative() && !ttl.isZero(), "ttl 必须大于 0");

        Long result = redissonClient.getScript(StringCodec.INSTANCE).eval(
            RScript.Mode.READ_WRITE,
            script,
            RScript.ReturnType.INTEGER,
            List.of(key),
            ownerToken,
            String.valueOf(ttl.toMillis())
        );
        return result != null ? result : 0L;
    }
}
