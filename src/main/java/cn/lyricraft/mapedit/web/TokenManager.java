package cn.lyricraft.mapedit.web;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Web 临时 token。
 * 每玩家仅一个有效 token：再签发即吊销旧 token；服务重启全部失效。
 * 续期（滑动）时实时复查生成者在线与权限；持有过期/吊销 token 一律 401。
 */
public final class TokenManager {

    public record Session(UUID owner, Instant expiresAt) {
        boolean expired() {
            return Instant.now().isAfter(expiresAt);
        }
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Session> tokens = new ConcurrentHashMap<>();
    private final Map<UUID, String> ownerToken = new ConcurrentHashMap<>();
    private final String requiredPermission;
    private final Duration ttl;

    public TokenManager(String requiredPermission, Duration ttl) {
        this.requiredPermission = requiredPermission;
        this.ttl = ttl;
    }

    /** 签发/换发：吊销该玩家旧 token，返回新 token */
    public String issue(UUID ownerUuid) {
        revokeOwner(ownerUuid);
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = java.util.HexFormat.of().formatHex(raw);
        tokens.put(token, new Session(ownerUuid, Instant.now().plus(ttl)));
        ownerToken.put(ownerUuid, token);
        return token;
    }

    /** token 是否有效且未过期 */
    public Session session(String token) {
        if (token == null) {
            return null;
        }
        Session s = tokens.get(token);
        if (s == null || s.expired()) {
            revoke(token);
            return null;
        }
        return s;
    }

    /**
     * 续期：生成者需在线且仍具权限；否则吊销返回 false。
     * 玩家主动获取新 token（issue）后旧 token 因 ownerToken 覆盖即失效——由 valid 校验兜底。
     */
    public boolean renew(String token) {
        Session s = session(token);
        if (s == null) {
            return false;
        }
        Player owner = Bukkit.getPlayer(s.owner());
        if (owner == null || !owner.isOnline() || !owner.hasPermission(requiredPermission)) {
            revoke(token);
            return false;
        }
        // 仅当仍是最新 token 才续期（防旧 token 复活）
        if (!token.equals(ownerToken.get(s.owner()))) {
            revoke(token);
            return false;
        }
        tokens.put(token, new Session(s.owner(), Instant.now().plus(ttl)));
        return true;
    }

    public void revoke(String token) {
        Session s = tokens.remove(token);
        if (s != null) {
            ownerToken.remove(s.owner(), token);
        }
    }

    private void revokeOwner(UUID ownerUuid) {
        String old = ownerToken.remove(ownerUuid);
        if (old != null) {
            tokens.remove(old);
        }
    }

    public void clear() {
        tokens.clear();
        ownerToken.clear();
    }
}
