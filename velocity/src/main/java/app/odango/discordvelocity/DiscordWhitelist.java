package app.odango.discordvelocity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** UUIDs reach this store only after Velocity has authenticated the Minecraft connection. */
final class DiscordWhitelist {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final SecureRandom RANDOM = new SecureRandom();
    private final Path path;
    private final Clock clock;
    private Map<UUID, Account> accounts;
    private final Map<String, Pending> pending = new HashMap<>();

    record Account(String minecraftName, String discordId) {}
    private record Pending(UUID uuid, String name, Instant expires) {}

    DiscordWhitelist(Path path) throws IOException {
        this(path, Clock.systemUTC());
    }

    DiscordWhitelist(Path path, Clock clock) throws IOException {
        this.path = path;
        this.clock = clock;
        accounts = new HashMap<>();
        if (Files.notExists(path)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            var json = JsonParser.parseReader(reader);
            if (!json.isJsonObject()) {
                throw new JsonParseException("Whitelist must be a JSON object.");
            }
            accounts = GSON.fromJson(json, new TypeToken<Map<UUID, Account>>() {}.getType());
            HashSet<String> discordIds = new HashSet<>();
            for (var entry : accounts.entrySet()) {
                Account account = entry.getValue();
                if (entry.getKey() == null || account == null || account.minecraftName() == null
                        || !account.minecraftName().matches("[A-Za-z0-9_]{1,16}")
                        || account.discordId() == null || !account.discordId().matches("[0-9]{1,20}")
                        || !discordIds.add(account.discordId())) {
                    throw new JsonParseException("Invalid or duplicate whitelist account.");
                }
            }
        } catch (JsonParseException | IllegalArgumentException exception) {
            throw new IOException("Invalid whitelist: " + path, exception);
        }
    }

    synchronized boolean contains(UUID uuid) {
        return accounts.containsKey(uuid);
    }

    synchronized String issueCode(UUID uuid, String name) {
        Instant now = clock.instant();
        pending.values().removeIf(value -> !value.expires().isAfter(now));
        for (var entry : pending.entrySet()) {
            if (entry.getValue().uuid().equals(uuid)) {
                return entry.getKey();
            }
        }
        if (pending.size() >= 1000) {
            throw new IllegalStateException("認証待ちが多いため、しばらくしてから再接続してください。");
        }
        String code;
        do {
            byte[] bytes = new byte[8];
            RANDOM.nextBytes(bytes);
            code = HexFormat.of().withUpperCase().formatHex(bytes);
        } while (pending.containsKey(code));
        pending.put(code, new Pending(uuid, name, now.plusSeconds(600)));
        return code;
    }

    synchronized UUID link(String code, String discordId) throws IOException {
        if (code == null || !code.matches("(?i)[0-9a-f]{16}") || !discordId.matches("[0-9]{1,20}")) {
            throw new IllegalArgumentException("認証コードが正しくありません。");
        }
        String key = code.toUpperCase(java.util.Locale.ROOT);
        Pending request = pending.get(key);
        if (request == null || !request.expires().isAfter(clock.instant())) {
            pending.remove(key);
            throw new IllegalArgumentException("コードが無効か期限切れです。Minecraftから再接続してください。");
        }
        if (accounts.containsKey(request.uuid())
                || accounts.values().stream().anyMatch(account -> account.discordId().equals(discordId))) {
            throw new IllegalArgumentException("既に登録済みです。別のアカウントに変更する場合は先に認証解除してください。");
        }
        Map<UUID, Account> updated = new HashMap<>(accounts);
        updated.put(request.uuid(), new Account(request.name(), discordId));
        save(updated);
        pending.remove(key);
        return request.uuid();
    }

    synchronized UUID unlink(String discordId) throws IOException {
        UUID uuid = accounts.entrySet().stream()
                .filter(entry -> entry.getValue().discordId().equals(discordId))
                .map(Map.Entry::getKey).findFirst().orElse(null);
        if (uuid == null) {
            throw new IllegalArgumentException("このDiscordアカウントは登録されていません。");
        }
        Map<UUID, Account> updated = new HashMap<>(accounts);
        updated.remove(uuid);
        save(updated);
        return uuid;
    }

    private void save(Map<UUID, Account> updated) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "whitelist-", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(updated));
            // Never replace the live in-memory list until the complete file has been committed.
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            accounts = updated;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
