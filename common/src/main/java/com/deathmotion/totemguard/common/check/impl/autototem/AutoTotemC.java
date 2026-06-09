/*
 * This file is part of TotemGuard - https://github.com/Bram1903/TotemGuard
 * Copyright (C) 2026 Bram and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.deathmotion.totemguard.common.check.impl.autototem;

import com.deathmotion.totemguard.api.check.CheckType;
import com.deathmotion.totemguard.common.check.HeuristicCheck;
import com.deathmotion.totemguard.common.check.annotations.CheckData;
import com.deathmotion.totemguard.common.check.type.ExtendedCheck;
import com.deathmotion.totemguard.common.player.TGPlayer;
import com.deathmotion.totemguard.common.player.inventory.InventoryConstants;
import com.deathmotion.totemguard.common.player.inventory.slot.InventorySlot;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;

@CheckData(description = "Synthetic offhand totem swap", type = CheckType.AUTO_TOTEM)
public class AutoTotemC extends HeuristicCheck implements ExtendedCheck {

    private static final int TOTEM_OF_UNDYING_STATUS = 35;
    private static final long MAX_AFTER_POP_MS = 2_000L;
    private static final long RESTORE_WINDOW_MS = 650L;
    private static final long MAX_SWITCH_TO_SWAP_MS = 450L;
    private static final long FAST_PACKET_MS = 75L;
    private static final long CLEAN_PACKET_MS = 150L;
    private static final long RESTOCK_WINDOW_MS = 5_000L;
    private static final int SAMPLE_SIZE = 4;

    private @Nullable Long popTimestamp;
    private long lastStatusPopAt = -1L;

    private long lastSwapPacketAt = -1L;
    private long lastSlotChangeAt = -1L;
    private int lastSlotChangeTo = -1;
    private int lastSlotChangeFrom = -1;
    private int knownSelectedHotbarIndex;

    private boolean awaitingRestore;
    private long pendingSwapAt = -1L;
    private int pendingSourceHotbar = -1;
    private int pendingRestoreHotbar = -1;
    private long pendingPopDelay = -1L;
    private long pendingSwitchDelay = -1L;
    private double pendingBaseWeight;

    private long lastOffhandActionAt = -1L;
    private int lastConsumedHotbar = -1;

    private int lastProfileSource = -1;
    private int lastProfileRestore = -1;
    private int repeatedProfileStreak;

    private final Deque<Long> popDelaySamples = new ArrayDeque<>(SAMPLE_SIZE);
    private final Deque<Long> switchDelaySamples = new ArrayDeque<>(SAMPLE_SIZE);
    private final Deque<Long> restoreDelaySamples = new ArrayDeque<>(SAMPLE_SIZE);

    public AutoTotemC(TGPlayer player) {
        super(player);
        this.knownSelectedHotbarIndex = inventory.getSelectedHotbarIndex();
    }

    @Override
    protected double flagThreshold() {
        return 5.0D;
    }

    @Override
    protected double decayPerSecond() {
        return 0.08D;
    }

    @Override
    public void onTotemActivated(long timestamp) {
        if (lastStatusPopAt >= 0L && Math.abs(timestamp - lastStatusPopAt) <= MAX_AFTER_POP_MS) {
            return;
        }
        armPop(timestamp);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.ENTITY_STATUS) return;

        WrapperPlayServerEntityStatus packet = new WrapperPlayServerEntityStatus(event);
        if (packet.getEntityId() != player.getUser().getEntityId()) return;
        if (packet.getStatus() != TOTEM_OF_UNDYING_STATUS) return;

        lastStatusPopAt = event.getTimestamp();
        armPop(lastStatusPopAt);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        PacketTypeCommon type = event.getPacketType();
        expirePendingRestore(event.getTimestamp());

        if (isTickBoundary(type)) {
            clearTickState();
            return;
        }

        if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            handleHeldItemChange(event);
            return;
        }

        if (type == PacketType.Play.Client.CLICK_WINDOW) {
            handleClickWindow(event);
            return;
        }

        if (type != PacketType.Play.Client.PLAYER_DIGGING) return;

        WrapperPlayClientPlayerDigging packet = new WrapperPlayClientPlayerDigging(event);
        switch (packet.getAction()) {
            case SWAP_ITEM_WITH_OFFHAND -> {
                lastSwapPacketAt = event.getTimestamp();
                handleOffhandActionSwap(lastSwapPacketAt);
            }
            default -> {
            }
        }
    }

    private void handleHeldItemChange(PacketReceiveEvent event) {
        WrapperPlayClientHeldItemChange packet = new WrapperPlayClientHeldItemChange(event);
        int slot = packet.getSlot();
        if (slot < 0 || slot > 8) return;

        long timestamp = event.getTimestamp();
        detectRestore(slot, timestamp);

        lastSlotChangeAt = timestamp;
        lastSlotChangeFrom = knownSelectedHotbarIndex;
        lastSlotChangeTo = slot;
        knownSelectedHotbarIndex = slot;
    }

    private void handleOffhandActionSwap(long timestamp) {
        Long popAt = activePop(timestamp);
        if (popAt == null) {
            clearPendingRestore();
            return;
        }

        int sourceHotbar = inventory.getSelectedHotbarIndex();
        int sourceSlot = InventoryConstants.HOTBAR_START + sourceHotbar;
        boolean switchedToSource = hasRecentSlotChange(timestamp)
                && lastSlotChangeTo == sourceHotbar
                && lastSlotChangeFrom != sourceHotbar;
        boolean sourceHadTotem = hadTotemBeforeSwap(sourceSlot);
        boolean offhandFilled = offhandChangedToTotem(timestamp);

        if (!sourceHadTotem && !offhandFilled) {
            clearPendingRestore();
            return;
        }

        long popDelay = timestamp - popAt;
        long switchDelay = switchedToSource ? timestamp - lastSlotChangeAt : -1L;
        double weight = scoreSwap(popDelay, switchDelay, switchedToSource, sourceHadTotem, offhandFilled);

        lastOffhandActionAt = timestamp;
        lastConsumedHotbar = sourceHotbar;
        popTimestamp = null;

        if (switchedToSource) {
            int restoreHotbar = lastSlotChangeFrom;
            weight += repeatedProfileBonus(sourceHotbar, restoreHotbar);
            awaitingRestore = true;
            pendingSwapAt = timestamp;
            pendingSourceHotbar = sourceHotbar;
            pendingRestoreHotbar = restoreHotbar;
            pendingPopDelay = popDelay;
            pendingSwitchDelay = switchDelay;
            pendingBaseWeight = weight;
        } else {
            awaitingRestore = false;
            pendingSourceHotbar = sourceHotbar;
            pendingRestoreHotbar = -1;
            pendingPopDelay = popDelay;
            pendingSwitchDelay = -1L;
            pendingBaseWeight = weight;
        }

        punish(weight,
                "offhand action,popDelay={0}ms,switchDelay={1}ms,source={2},restore={3}",
                popDelay, switchDelay, sourceHotbar, pendingRestoreHotbar);
    }

    private void handleClickWindow(PacketReceiveEvent event) {
        WrapperPlayClientClickWindow packet = new WrapperPlayClientClickWindow(event);
        if (player.isModDetectionWindow(packet.getWindowId())) return;
        if (packet.getWindowId() != InventoryConstants.PLAYER_WINDOW_ID) return;
        if (packet.getWindowClickType() != WrapperPlayClientClickWindow.WindowClickType.SWAP) return;

        int targetHotbar = packet.getButton();
        if (targetHotbar < 0 || targetHotbar > 8) return;
        if (targetHotbar != lastConsumedHotbar) return;

        long timestamp = event.getTimestamp();
        long sinceSwap = timestamp - lastOffhandActionAt;
        if (sinceSwap < 0L || sinceSwap > RESTOCK_WINDOW_MS) return;

        int hotbarSlot = InventoryConstants.HOTBAR_START + targetHotbar;
        if (!slotChangedToTotem(hotbarSlot, timestamp, RESTOCK_WINDOW_MS)) return;

        punish(0.75D,
                "post-swap hotbar restock,delay={0}ms,target={1},slot={2}",
                sinceSwap, targetHotbar, packet.getSlot());
    }

    private double scoreSwap(long popDelay,
                             long switchDelay,
                             boolean switchedToSource,
                             boolean sourceHadTotem,
                             boolean offhandFilled) {
        double weight = 0.20D;

        if (offhandFilled) weight += 0.25D;
        if (sourceHadTotem) weight += 0.15D;

        if (popDelay <= FAST_PACKET_MS) {
            weight += 0.60D;
        } else if (popDelay <= CLEAN_PACKET_MS) {
            weight += 0.40D;
        } else if (popDelay <= 300L) {
            weight += 0.20D;
        }

        if (switchedToSource) {
            if (switchDelay <= FAST_PACKET_MS) {
                weight += 0.65D;
            } else if (switchDelay <= CLEAN_PACKET_MS) {
                weight += 0.45D;
            } else {
                weight += 0.20D;
            }
        }

        return weight;
    }

    private void detectRestore(int slot, long timestamp) {
        if (!awaitingRestore) return;

        long restoreDelay = timestamp - pendingSwapAt;
        if (restoreDelay < 0L || restoreDelay > RESTORE_WINDOW_MS) {
            clearPendingRestore();
            return;
        }
        if (slot != pendingRestoreHotbar || slot == pendingSourceHotbar) return;

        double restoreWeight = restoreDelay <= FAST_PACKET_MS
                ? 0.85D
                : restoreDelay <= CLEAN_PACKET_MS ? 0.60D : 0.30D;
        double consistency = consistencyBonus(pendingPopDelay, pendingSwitchDelay, restoreDelay);

        punish(restoreWeight + consistency,
                "offhand action restore,popDelay={0}ms,switchDelay={1}ms,restoreDelay={2}ms,source={3},restore={4},base={5}",
                pendingPopDelay, pendingSwitchDelay, restoreDelay, pendingSourceHotbar, pendingRestoreHotbar,
                String.format("%.2f", pendingBaseWeight));

        clearPendingRestore();
    }

    private double repeatedProfileBonus(int sourceHotbar, int restoreHotbar) {
        if (sourceHotbar == lastProfileSource && restoreHotbar == lastProfileRestore) {
            repeatedProfileStreak++;
        } else {
            lastProfileSource = sourceHotbar;
            lastProfileRestore = restoreHotbar;
            repeatedProfileStreak = 1;
        }

        return repeatedProfileStreak >= 3 ? 0.35D : repeatedProfileStreak == 2 ? 0.20D : 0.0D;
    }

    private double consistencyBonus(long popDelay, long switchDelay, long restoreDelay) {
        addSample(popDelaySamples, popDelay);
        addSample(switchDelaySamples, switchDelay);
        addSample(restoreDelaySamples, restoreDelay);

        if (restoreDelaySamples.size() < 3) return 0.0D;

        double popStdDev = stdDev(popDelaySamples);
        double switchStdDev = stdDev(switchDelaySamples);
        double restoreStdDev = stdDev(restoreDelaySamples);

        if (popStdDev <= 25.0D && switchStdDev <= 15.0D && restoreStdDev <= 15.0D) {
            return 0.80D;
        }
        if (popStdDev <= 50.0D && switchStdDev <= 30.0D && restoreStdDev <= 30.0D) {
            return 0.45D;
        }
        return 0.0D;
    }

    private void addSample(Deque<Long> samples, long sample) {
        if (sample < 0L) return;
        if (samples.size() >= SAMPLE_SIZE) {
            samples.removeFirst();
        }
        samples.addLast(sample);
    }

    private double stdDev(Deque<Long> samples) {
        if (samples.isEmpty()) return 0.0D;

        double mean = 0.0D;
        for (long sample : samples) {
            mean += sample;
        }
        mean /= samples.size();

        double variance = 0.0D;
        for (long sample : samples) {
            double diff = sample - mean;
            variance += diff * diff;
        }
        return Math.sqrt(variance / samples.size());
    }

    private void expirePendingRestore(long timestamp) {
        if (!awaitingRestore) return;
        if (timestamp - pendingSwapAt > RESTORE_WINDOW_MS) {
            clearPendingRestore();
        }
    }

    private void clearPendingRestore() {
        awaitingRestore = false;
        pendingSwapAt = -1L;
        pendingSourceHotbar = -1;
        pendingRestoreHotbar = -1;
        pendingPopDelay = -1L;
        pendingSwitchDelay = -1L;
        pendingBaseWeight = 0.0D;
    }

    private @Nullable Long activePop(long timestamp) {
        Long popAt = popTimestamp;
        if (popAt == null) return null;

        long delay = timestamp - popAt;
        if (delay < 0L || delay > MAX_AFTER_POP_MS) {
            popTimestamp = null;
            return null;
        }
        return popAt;
    }

    private boolean hasRecentSlotChange(long timestamp) {
        return lastSlotChangeAt >= 0L
                && lastSlotChangeAt <= timestamp
                && timestamp - lastSlotChangeAt <= MAX_SWITCH_TO_SWAP_MS;
    }

    private boolean hadTotemBeforeSwap(int slot) {
        InventorySlot inventorySlot = inventory.getSlots().get(slot);
        if (inventorySlot == null) return false;
        return inventorySlot.getPrevious().item().getType() == ItemTypes.TOTEM_OF_UNDYING
                && inventorySlot.getUpdated() >= lastSwapPacketAt - RESTORE_WINDOW_MS;
    }

    private boolean offhandChangedToTotem(long timestamp) {
        return slotChangedToTotem(InventoryConstants.SLOT_OFFHAND, timestamp, RESTORE_WINDOW_MS);
    }

    private boolean slotChangedToTotem(int slot, long timestamp, long windowMs) {
        InventorySlot inventorySlot = inventory.getSlots().get(slot);
        if (inventorySlot == null) return false;
        if (inventorySlot.getItem().getType() != ItemTypes.TOTEM_OF_UNDYING) return false;
        if (inventorySlot.getPrevious().item().getType() == ItemTypes.TOTEM_OF_UNDYING) return false;
        return Math.abs(inventorySlot.getUpdated() - timestamp) <= windowMs;
    }

    private void armPop(long timestamp) {
        popTimestamp = timestamp;
        clearPendingRestore();
    }

    private boolean isTickBoundary(PacketTypeCommon type) {
        return player.supportsEndTick()
                ? type == PacketType.Play.Client.CLIENT_TICK_END
                : WrapperPlayClientPlayerFlying.isFlying(type);
    }

    private void clearTickState() {
        lastSwapPacketAt = -1L;
    }
}
