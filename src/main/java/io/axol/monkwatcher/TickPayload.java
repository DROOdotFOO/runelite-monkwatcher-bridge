package io.axol.monkwatcher;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TickPayload {
    public final int v = 1;
    public long t;
    public int tick;
    public int anim = -1;
    public int poseAnim = -1;
    public int mouseIdleTicks;
    public int kbIdleTicks;
    public Integer npc;        // null when not interacting
    public boolean isMonk;
    public int x;
    public int y;
    public int plane;
    public int hp;
    public int maxHp;
    public int prayer;
    public int maxPrayer;
    public int runEnergy;
}
