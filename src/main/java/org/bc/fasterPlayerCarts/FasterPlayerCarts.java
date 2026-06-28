package org.bc.fasterPlayerCarts;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.CommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Rail;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;

/**
 * initial code from "package com.lynxxdg.fastPlayerMinecarts;": https://modrinth.com/plugin/lynx-minecarts
 *
 * Controls options for faster minecarts.
 * Note: minecarts do not get affected when going up blocks/on ramps
 *
 * @author BC/EXO - Modifier, lynxxdg - Initial Creator
 */
public class FasterPlayerCarts extends JavaPlugin implements Listener {
    public int maxIter;
    public double defaultSpeed;
    public double breakSpeed;

    public static Map<String, Double> cartOptions = new HashMap<>();

    FileConfiguration config;

    private void loadConfig(FileConfiguration config, CommandSender sender) {
        // make sure to save the actual file so changes made in it persist
        this.saveDefaultConfig();

        // reload it from disk cause the data in memory will not match the disk if changed from the config file
        this.reloadConfig();

        // get newest config?
        config = getConfig();

        cartOptions.clear();
        maxIter = config.getInt("maxIterations", 1);
        defaultSpeed = config.getDouble("defaultSpeed", 8.0);
        // translate speed to blocks per second (i.e. speed / default tps)
        this.defaultSpeed = defaultSpeed / 20.0;
        this.breakSpeed = config.getDouble("breakSpeed") / 20.0;

        for (String cartOptionKeyName : config.getConfigurationSection("carts").getKeys(false)) {
            String name = config.getString(String.format("carts.%s.name", cartOptionKeyName));
            // translate speed to blocks per tick (i.e. speed / default tps)
            double speed = config.getDouble(String.format("carts.%s.speed", cartOptionKeyName)) / 20.0;
            cartOptions.put(name, speed);
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();

        config = this.getConfig();
        loadConfig(config, getServer().getConsoleSender());

        LiteralArgumentBuilder<CommandSourceStack> reloadCommand = Commands.literal("fasterPlayerCarts").then(Commands.literal("reloadConfig")
                .requires(sender -> sender.getSender().hasPermission("fastercarts.reload"))
                .executes(ctx -> {
                    loadConfig(config, ctx.getSource().getSender());
                    ctx.getSource().getSender().sendRichMessage("<green>Reload Complete!");
                    return Command.SINGLE_SUCCESS;
                })
        );

        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, commands -> {
            commands.registrar().register(reloadCommand.build());
        });

        getServer().getPluginManager().registerEvents(this, this);
    }
    private boolean isValidCart(Vehicle vehicle) {
        if (!(vehicle instanceof Minecart minecart) || !cartOptions.containsKey(minecart.getName())) return false;
        var passengers = vehicle.getPassengers();
        int i = 0;
        while (i++ < maxIter && !passengers.isEmpty() && !(passengers.getFirst() instanceof Player))
            passengers = passengers.getFirst().getPassengers();
        return i <= maxIter && !passengers.isEmpty();
    }
    @EventHandler
    private void onVehicleEnter(VehicleEnterEvent event) {
        if (isValidCart(event.getVehicle())) {
            ((Minecart) event.getVehicle()).setMaxSpeed(cartOptions.get(event.getVehicle().getName()));
        }
    }
    @EventHandler
    private void onVehicleExit(VehicleExitEvent event) {
        if (!(event.getVehicle() instanceof Minecart minecart)) return;
        minecart.setMaxSpeed(defaultSpeed);
    }
    private Vector capSpeed(Vector vel, double speed) {
        vel = vel.clone();
        if (vel.length() > speed) vel = vel.normalize().multiply(speed);
        return vel;
    }

    private boolean isAscendingRail(Block block) {
        if (block.getBlockData() instanceof Rail rail) {
            Rail.Shape shape = rail.getShape();
            return (shape.equals(Rail.Shape.ASCENDING_EAST) || shape.equals(Rail.Shape.ASCENDING_WEST) || shape.equals(Rail.Shape.ASCENDING_NORTH) || shape.equals(Rail.Shape.ASCENDING_SOUTH));
        }
        return false;
    }

    private boolean isPowered(Block block) {
        if (block.getBlockData() instanceof Rail rail) {
            return rail.getMaterial() == Material.POWERED_RAIL;
        }
        return false;
    }

    private boolean shouldBrakeForBlock(Block block) {
        if (block.getBlockData() instanceof Rail rail) {
            Rail.Shape shape = rail.getShape();
            // the "isPowered" causes an issue when a normal rail slants up onto a powered straight rail, so simply disable it
            return shape.equals(Rail.Shape.NORTH_EAST) || shape.equals(Rail.Shape.SOUTH_EAST) || shape.equals(Rail.Shape.SOUTH_WEST) || shape.equals(Rail.Shape.NORTH_WEST) || (/*isPowered(block) && */isAscendingRail(block));
        }
        return false;
    }

    @EventHandler
    private void onVehicleMove(VehicleMoveEvent event) {
        if (!isValidCart(event.getVehicle())) {
            if (event.getVehicle() instanceof Minecart minecart) minecart.setMaxSpeed(defaultSpeed);
            return;
        }
        var minecart = (Minecart)event.getVehicle(); // if previous condition is true, vehicle must be minecart

        Block toBlock = event.getTo().getBlock();
        Vector velocity = minecart.getVelocity();

        int modX = 0;
        int modZ = 0;
        if (velocity.getX() > 0.01) modX = 1;
        if (velocity.getX() < -0.01) modX = -1;
        if (velocity.getZ() > 0.01) modZ = 1;
        if (velocity.getZ() < -0.01) modZ = -1;

        Block nextBlock = toBlock.getRelative(modX, 0, modZ);
        if (shouldBrakeForBlock(toBlock) || shouldBrakeForBlock(nextBlock)) {
            minecart.setMaxSpeed(breakSpeed);
            Vector newVelocity = capSpeed(velocity, breakSpeed);
            event.getVehicle().setVelocity(newVelocity);
        } else {
            if (cartOptions.containsKey(minecart.getName())) {
                minecart.setMaxSpeed(cartOptions.get(event.getVehicle().getName()));
            }
        }
    }
}