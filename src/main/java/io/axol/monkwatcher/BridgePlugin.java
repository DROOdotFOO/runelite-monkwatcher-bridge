package io.axol.monkwatcher;

import com.google.inject.Provides;
import javax.inject.Inject;
import java.util.Map;
import java.util.Objects;

import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@PluginDescriptor(
    name = "Raxol Monk Watcher Bridge",
    description = "Streams local player state to Raxol over a Unix socket",
    tags = {"raxol", "personal", "idle"}
)
public class BridgePlugin extends Plugin {
    @Inject private Client client;
    @Inject private BridgeConfig config;
    @Inject private BridgeSocketServer server;

    @Override
    protected void startUp() throws Exception {
        server.start(config.socketPath());
    }

    @Override
    protected void shutDown() {
        server.stop();
    }

    @Subscribe
    public void onGameTick(GameTick event) {
        if (client.getGameState() != GameState.LOGGED_IN) return;
        Player me = client.getLocalPlayer();
        if (me == null) return;

        TickPayload p = new TickPayload();
        p.t = System.currentTimeMillis();
        p.tick = client.getTickCount();
        p.anim = me.getAnimation();
        p.poseAnim = me.getPoseAnimation();
        p.mouseIdleTicks = client.getMouseIdleTicks();
        p.kbIdleTicks = client.getKeyboardIdleTicks();

        if (me.getInteracting() instanceof NPC npc) {
            p.npc = npc.getId();
            p.isMonk = NpcCatalog.isMonk(npc.getId());
        }

        WorldPoint wp = me.getWorldLocation();
        p.x = wp.getX();
        p.y = wp.getY();
        p.plane = wp.getPlane();

        p.hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
        p.maxHp = client.getRealSkillLevel(Skill.HITPOINTS);
        p.prayer = client.getBoostedSkillLevel(Skill.PRAYER);
        p.maxPrayer = client.getRealSkillLevel(Skill.PRAYER);
        p.runEnergy = client.getEnergy() / 100;

        server.send(p);
    }

    @Subscribe
    public void onActorDeath(ActorDeath event) {
        Actor actor = event.getActor();
        if (actor == client.getLocalPlayer()) {
            server.sendEvent("player_death", null);
        } else if (actor instanceof NPC npc && NpcCatalog.isMonk(npc.getId())) {
            server.sendEvent("monk_killed", Map.of(
                "id", npc.getId(),
                "name", Objects.requireNonNullElse(npc.getName(), "Monk")
            ));
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        server.sendEvent("game_state", Map.of(
            "state", event.getGameState().name()
        ));
    }

    @Provides
    BridgeConfig provideConfig(ConfigManager cm) {
        return cm.getConfig(BridgeConfig.class);
    }
}
