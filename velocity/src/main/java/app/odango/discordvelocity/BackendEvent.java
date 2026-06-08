package app.odango.discordvelocity;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

final class BackendEvent {
    private static final Gson GSON = new Gson();

    String type;
    String player;
    String deathMessage;
    String world;
    int x;
    int y;
    int z;

    static BackendEvent fromJson(String json) {
        try {
            return GSON.fromJson(json, BackendEvent.class);
        } catch (JsonSyntaxException exception) {
            return null;
        }
    }
}
