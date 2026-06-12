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
import com.deathmotion.totemguard.common.player.inventory.enums.Issuer;
import com.deathmotion.totemguard.common.player.inventory.enums.SlotAction;
import com.deathmotion.totemguard.common.player.inventory.slot.CarriedItem;
import com.deathmotion.totemguard.common.player.inventory.slot.InventorySlot;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityStatus;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerHeldItemChange;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

@CheckData(description = "Synthetic offhand totem swap", type = CheckType.AUTO_TOTEM)
public class AutoTotemC extends HeuristicCheck implements ExtendedCheck {

    private static final int TOTEM_OF_UNDYING_STATUS = 35;
    private static final int MIN_HOTBAR_INDEX = 0;
    private static final int MAX_HOTBAR_INDEX = 8;
    private static final int NO_HOTBAR_INDEX = -1;
    private static final long NO_TIMESTAMP = -1L;
    private static final long MAX_AFTER_POP_MS = 1000L;
    private static final long RESTORE_WINDOW_MS = 350L;
    private static final long MAX_SWITCH_TO_SWAP_MS = 300L;
    private static final long DUPLICATE_OFFHAND_ACTION_MS = 5L;
    private static final long FAST_PACKET_MS = 75L;
    private static final long CLEAN_PACKET_MS = 150L;

    private long activePopAt = NO_TIMESTAMP;
    private long lastStatusPopAt = NO_TIMESTAMP;

    private long lastSlotChangeAt = NO_TIMESTAMP;
    private int lastSlotChangeTo = NO_HOTBAR_INDEX;
    private int lastSlotChangeFrom = NO_HOTBAR_INDEX;
    private int knownSelectedHotbarIndex;

    private boolean awaitingRestore;
    private long pendingSwapAt = NO_TIMESTAMP;
    private int pendingSourceHotbar = NO_HOTBAR_INDEX;
    private int pendingRestoreHotbar = NO_HOTBAR_INDEX;
    private long pendingPopDelay = NO_TIMESTAMP;
    private long pendingSwitchDelay = NO_TIMESTAMP;
    private double pendingBaseWeight;

    private long lastOffhandActionAt = NO_TIMESTAMP;

    public AutoTotemC(TGPlayer player) {
        super(player);
        this.knownSelectedHotbarIndex = inventory.getSelectedHotbarIndex();
    }

    @Override
    protected double flagThreshold() {
        return 4.0D;
    }

    @Override
    protected double decayPerSecond() {
        return 0.08D;
    }

    @Override
    public void onTotemActivated(long timestamp) {
        if (lastStatusPopAt != NO_TIMESTAMP && Math.abs(timestamp - lastStatusPopAt) <= MAX_AFTER_POP_MS) {
            return;
        }
        armPop(timestamp);
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        PacketTypeCommon type = event.getPacketType();
        if (type == PacketType.Play.Server.HELD_ITEM_CHANGE) {
            handleServerHeldItemChange(event);
            return;
        }
        if (type != PacketType.Play.Server.ENTITY_STATUS) return;

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

        if (type == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            handleHeldItemChange(event);
            return;
        }

        if (type != PacketType.Play.Client.PLAYER_DIGGING) return;

        WrapperPlayClientPlayerDigging packet = new WrapperPlayClientPlayerDigging(event);
        if (packet.getAction() == DiggingAction.SWAP_ITEM_WITH_OFFHAND) {
            handleOffhandActionSwap(event.getTimestamp());
        }
    }

    @Override
    public void onInventoryChanged(@Nullable CarriedItem updatedCarriedItem,
                                   @NotNull List<InventorySlot> changedSlots,
                                   @NotNull Issuer lastIssuer) {
        if (lastIssuer != Issuer.CLIENT) return;

        for (InventorySlot changedSlot : changedSlots) {
            if (changedSlot.getSlot() != InventoryConstants.SLOT_OFFHAND) continue;
            if (changedSlot.getSlotAction() != SlotAction.SWAP) continue;
            if (!changedToTotem(changedSlot)) continue;

            handleDetectedSwap(changedSlot.getUpdated(), true);
            return;
        }
    }

    private void handleHeldItemChange(PacketReceiveEvent event) {
        WrapperPlayClientHeldItemChange packet = new WrapperPlayClientHeldItemChange(event);
        int slot = packet.getSlot();
        if (!isHotbarIndex(slot)) return;

        long timestamp = event.getTimestamp();
        detectRestore(slot, timestamp);

        lastSlotChangeAt = timestamp;
        lastSlotChangeFrom = knownSelectedHotbarIndex;
        lastSlotChangeTo = slot;
        knownSelectedHotbarIndex = slot;
    }

    private void handleServerHeldItemChange(PacketSendEvent event) {
        WrapperPlayServerHeldItemChange packet = new WrapperPlayServerHeldItemChange(event);
        int slot = packet.getSlot();
        if (isHotbarIndex(slot)) {
            knownSelectedHotbarIndex = slot;
        }
    }

    private void handleOffhandActionSwap(long timestamp) {
        handleDetectedSwap(timestamp, offhandChangedToTotem(timestamp));
    }

    private void handleDetectedSwap(long timestamp, boolean offhandFilled) {
        if (isDuplicateOffhandAction(timestamp)) {
            return;
        }

        long popAt = activePop(timestamp);
        if (popAt == NO_TIMESTAMP) {
            clearPendingRestore();
            return;
        }

        int sourceHotbar = inventory.getSelectedHotbarIndex();
        if (!isHotbarIndex(sourceHotbar)) {
            clearPendingRestore();
            return;
        }

        boolean switchedToSource = hasRecentSlotChange(timestamp)
                && lastSlotChangeTo == sourceHotbar
                && isHotbarIndex(lastSlotChangeFrom)
                && lastSlotChangeFrom != sourceHotbar;

        if (!offhandFilled && !switchedToSource) {
            clearPendingRestore();
            return;
        }

        long popDelay = timestamp - popAt;
        long switchDelay = switchedToSource ? timestamp - lastSlotChangeAt : NO_TIMESTAMP;
        double weight = scoreSwap(popDelay, switchDelay, switchedToSource, offhandFilled);

        lastOffhandActionAt = timestamp;
        activePopAt = NO_TIMESTAMP;

        if (switchedToSource) {
            int restoreHotbar = lastSlotChangeFrom;
            awaitingRestore = true;
            pendingSwapAt = timestamp;
            pendingSourceHotbar = sourceHotbar;
            pendingRestoreHotbar = restoreHotbar;
            pendingPopDelay = popDelay;
            pendingSwitchDelay = switchDelay;
            pendingBaseWeight = weight;
            return;
        }

        clearPendingRestore();

        punish(weight,
                "offhand action,popDelay={0}ms,switchDelay={1}ms,source={2}",
                popDelay, switchDelay, sourceHotbar);
    }

    private double scoreSwap(long popDelay, long switchDelay, boolean switchedToSource, boolean offhandFilled) {
        double weight = 0.60D;

        if (offhandFilled) weight += 0.25D;

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
        if (slot != pendingRestoreHotbar) return;

        double restoreWeight = restoreDelay <= FAST_PACKET_MS
                ? 0.85D
                : restoreDelay <= CLEAN_PACKET_MS ? 0.60D : 0.30D;
        double totalWeight = pendingBaseWeight + restoreWeight;

        punish(totalWeight,
                "offhand action sandwich,popDelay={0}ms,switchDelay={1}ms,restoreDelay={2}ms,source={3},restore={4}",
                pendingPopDelay, pendingSwitchDelay, restoreDelay, pendingSourceHotbar, pendingRestoreHotbar);

        clearPendingRestore();
    }

    private void expirePendingRestore(long timestamp) {
        if (!awaitingRestore) return;
        if (timestamp - pendingSwapAt > RESTORE_WINDOW_MS) {
            clearPendingRestore();
        }
    }

    private void clearPendingRestore() {
        awaitingRestore = false;
        pendingSwapAt = NO_TIMESTAMP;
        pendingSourceHotbar = NO_HOTBAR_INDEX;
        pendingRestoreHotbar = NO_HOTBAR_INDEX;
        pendingPopDelay = NO_TIMESTAMP;
        pendingSwitchDelay = NO_TIMESTAMP;
        pendingBaseWeight = 0.0D;
    }

    private long activePop(long timestamp) {
        if (activePopAt == NO_TIMESTAMP) return NO_TIMESTAMP;

        long delay = timestamp - activePopAt;
        if (delay < 0L || delay > MAX_AFTER_POP_MS) {
            activePopAt = NO_TIMESTAMP;
            return NO_TIMESTAMP;
        }
        return activePopAt;
    }

    private boolean hasRecentSlotChange(long timestamp) {
        return lastSlotChangeAt != NO_TIMESTAMP
                && lastSlotChangeAt <= timestamp
                && timestamp - lastSlotChangeAt <= MAX_SWITCH_TO_SWAP_MS;
    }

    private boolean isHotbarIndex(int slot) {
        return slot >= MIN_HOTBAR_INDEX && slot <= MAX_HOTBAR_INDEX;
    }

    private boolean isDuplicateOffhandAction(long timestamp) {
        return lastOffhandActionAt != NO_TIMESTAMP
                && Math.abs(timestamp - lastOffhandActionAt) <= DUPLICATE_OFFHAND_ACTION_MS;
    }

    private boolean offhandChangedToTotem(long timestamp) {
        return slotChangedToTotem(InventoryConstants.SLOT_OFFHAND, timestamp, RESTORE_WINDOW_MS);
    }

    private boolean slotChangedToTotem(int slot, long timestamp, long windowMs) {
        InventorySlot inventorySlot = inventory.getSlots().get(slot);
        if (inventorySlot == null) return false;
        if (!changedToTotem(inventorySlot)) return false;
        long updated = inventorySlot.getUpdated();
        return updated <= timestamp && timestamp - updated <= windowMs;
    }

    private boolean changedToTotem(InventorySlot inventorySlot) {
        return inventorySlot.getItem().getType() == ItemTypes.TOTEM_OF_UNDYING
                && inventorySlot.getPrevious().item().getType() != ItemTypes.TOTEM_OF_UNDYING;
    }

    private void armPop(long timestamp) {
        activePopAt = timestamp;
        clearPendingRestore();
    }
}
