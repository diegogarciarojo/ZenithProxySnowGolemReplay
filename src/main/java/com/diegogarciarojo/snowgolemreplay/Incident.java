package com.diegogarciarojo.snowgolemreplay;

import java.util.List;

record Incident(String id, String timeUtc, String timeLocal, String uuid, int entityId,
                String dimension, double x, double y, double z, String confirmation,
                long replayTimestampMs, long availablePreMs, int requestedPreSeconds,
                boolean completePreHistory, List<Damage> recentDamage, String circumstances) {
    boolean isDeath() { return !confirmation.startsWith("MANUAL_"); }
    record Damage(String timeUtc, long ageAtDeathMs, String type, int typeId,
                  int causeId, String cause, int directId, String direct, String sourcePosition) {}
}
