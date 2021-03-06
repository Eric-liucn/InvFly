package com.github.ericliucn.invfly.commands;

import com.github.ericliucn.invfly.Invfly;
import com.github.ericliucn.invfly.config.Message;
import org.spongepowered.api.command.CommandResult;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.command.args.CommandContext;
import org.spongepowered.api.command.spec.CommandExecutor;
import org.spongepowered.api.command.spec.CommandSpec;

public class ReloadCommand implements CommandExecutor {

    @Override
    public CommandResult execute(CommandSource src, CommandContext args) {
        Message message = Invfly.instance.getConfigLoader().getMessage();
        Invfly.instance.getAsyncExecutor().submit(()->{
            try {
                Invfly.instance.reload();
                src.sendMessage(message.getMessage("command.reload.success"));
            }catch (Exception e){
                e.printStackTrace();
                src.sendMessage(message.getMessage("command.reload.fail"));
            }
        });
        return CommandResult.success();
    }

    public static CommandSpec build(){
        return CommandSpec.builder()
                .permission("invfly.command.reload")
                .executor(new ReloadCommand())
                .build();
    }
}
