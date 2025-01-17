package net.earthcomputer.clientcommands.command;

import com.google.common.collect.Iterables;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.network.chat.TextComponent;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.server.command.ServerCommandSource;

import java.util.Map;

import static com.mojang.brigadier.arguments.StringArgumentType.*;
import static net.earthcomputer.clientcommands.command.ClientCommandManager.*;
import static net.minecraft.server.command.CommandManager.*;

public class CHelpCommand {

    private static final SimpleCommandExceptionType FAILED_EXCEPTION = new SimpleCommandExceptionType(new TranslatableComponent("commands.help.failed"));

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        addClientSideCommand("chelp");

        dispatcher.register(literal("chelp")
            .executes(ctx -> {
                int cmdCount = 0;
                for (CommandNode<ServerCommandSource> command : dispatcher.getRoot().getChildren()) {
                    String cmdName = command.getName();
                    if (isClientSideCommand(cmdName)) {
                        Map<CommandNode<ServerCommandSource>, String> usage = dispatcher.getSmartUsage(command, ctx.getSource());
                        for (String u : usage.values()) {
                            sendFeedback(new TextComponent("/" + cmdName + " " + u));
                        }
                        cmdCount += usage.size();
                        if (usage.size() == 0) {
                            sendFeedback(new TextComponent("/" + cmdName));
                            cmdCount++;
                        }
                    }
                }
                return cmdCount;
            })
            .then(argument("command", greedyString())
                .executes(ctx -> {
                    String cmdName = getString(ctx, "command");
                    if (!isClientSideCommand(cmdName))
                        throw FAILED_EXCEPTION.create();

                    ParseResults<ServerCommandSource> parseResults = dispatcher.parse(cmdName, ctx.getSource());
                    if (parseResults.getContext().getNodes().isEmpty())
                        throw FAILED_EXCEPTION.create();

                    Map<CommandNode<ServerCommandSource>, String> usage = dispatcher.getSmartUsage(Iterables.getLast(parseResults.getContext().getNodes()).getNode(), ctx.getSource());
                    for (String u : usage.values()) {
                        sendFeedback(new TextComponent("/" + cmdName + " " + u));
                    }

                    return usage.size();
                })));
    }

}
