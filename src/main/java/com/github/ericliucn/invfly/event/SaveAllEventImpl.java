package com.github.ericliucn.invfly.event;

import com.github.ericliucn.invfly.Invfly;
import com.github.ericliucn.invfly.api.SaveAllEvent;
import com.github.ericliucn.invfly.api.SyncData;
import com.github.ericliucn.invfly.data.EnumResult;
import com.github.ericliucn.invfly.data.StorageData;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.entity.living.player.User;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.cause.EventContext;
import org.spongepowered.api.event.cause.EventContextKeys;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SaveAllEventImpl implements SaveAllEvent {

    private final User user;
    private final List<SyncData> dataList;
    private final UUID taskUUID;
    private final StorageData data;
    private final CommandSource source;

    public SaveAllEventImpl(User user, UUID taskUUID, List<SyncData> dataList, StorageData data, CommandSource source){
        this.user = user;
        this.taskUUID = taskUUID;
        this.data = data;
        this.dataList = dataList;
        this.source = source;
    }

    @Override
    public CommandSource getCmdSource() {
        return this.source;
    }

    @Override
    public UUID getTaskUUID() {
        return taskUUID;
    }

    @Override
    public StorageData getStorageData() {
        return data;
    }

    @Override
    public List<SyncData> getSyncDataList() {
        return dataList;
    }

    @Override
    public User getTargetUser() {
        return user;
    }

    @Override
    public Cause getCause() {
        EventContext context = EventContext.builder().add(EventContextKeys.PLUGIN, Invfly.instance.getPluginContainer()).build();
        return Cause.builder().append(Invfly.instance).build(context);
    }

    public static class Pre extends SaveAllEventImpl implements SaveAllEvent.Pre{

        public Pre(User user, UUID taskUUID, List<SyncData> dataList, StorageData data, CommandSource source){
            super(user, taskUUID, dataList, data, source);
        }
    }

    public static class Done extends SaveAllEventImpl implements SaveAllEvent.Done{

        private final Map<SyncData, EnumResult> resultMap;

        public Done(User user, UUID taskUUID, List<SyncData> dataList, StorageData data, Map<SyncData, EnumResult> resultMap, CommandSource source){
            super(user, taskUUID, dataList, data, source);
            this.resultMap = resultMap;
        }

        @Override
        public Map<SyncData, EnumResult> getResults() {
            return resultMap;
        }
    }


}
