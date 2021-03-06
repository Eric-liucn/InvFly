package com.github.ericliucn.invfly.service;

import com.github.ericliucn.invfly.Invfly;
import com.github.ericliucn.invfly.api.SyncData;
import com.github.ericliucn.invfly.data.EnumResult;
import com.github.ericliucn.invfly.data.StorageData;
import com.github.ericliucn.invfly.event.LoadAllEventImpl;
import com.github.ericliucn.invfly.event.LoadSingleEvent;
import com.github.ericliucn.invfly.exception.DeserializeException;
import com.github.ericliucn.invfly.exception.NoSuchDataException;
import com.github.ericliucn.invfly.utils.Utils;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.entity.living.player.User;
import org.spongepowered.api.scheduler.SpongeExecutorService;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class LoadResult {

    private final UUID taskUUID;
    private final List<SyncData> dataList;
    private final User user;
    private final StorageData data;
    private final Map<SyncData, Future<EnumResult>> resultFutureMap = new ConcurrentHashMap<>();
    private final Map<SyncData, EnumResult> resultMap = new ConcurrentHashMap<>();
    private final SpongeExecutorService async;
    private final SpongeExecutorService sync;
    private final boolean checkPermission;
    private final AtomicInteger finishCount;
    private final int dataSize;
    private boolean postAlready;
    private final int timeOut;
    private final CommandSource source;

    public LoadResult(UUID uuid, List<SyncData> dataList, User user, StorageData data, SpongeExecutorService async, SpongeExecutorService sync, boolean checkPermission, CommandSource source){
        this.dataList = dataList;
        this.dataSize = dataList.size();
        this.checkPermission = checkPermission;
        this.async = async;
        this.sync = sync;
        this.user = user;
        this.data = data;
        this.taskUUID = uuid;
        this.source = source;
        this.finishCount = new AtomicInteger(0);
        this.timeOut = Invfly.instance.getConfigLoader().getConfig().general.loadTimeOut;
        new LoadAllEventImpl.Pre(uuid, user, dataList, data, source);
        this.timeOutTask();
        this.load();
    }

    private void timeOutTask(){
        async.schedule(() -> {

            if (!postAlready){
                resultFutureMap.entrySet()
                        .stream()
                        .filter(entry -> !entry.getValue().isDone())
                        .map(entry -> {
                            entry.getValue().cancel(false);
                            resultMap.put(entry.getKey(), EnumResult.FAIL);
                            return null;
                        })
                        .close();
                sync.submit(() -> new LoadAllEventImpl.Done(taskUUID, user, dataList, data, resultMap, source));
            }
        }, this.timeOut, TimeUnit.SECONDS);

    }

    private void load(){
        for (SyncData syncData:dataList){
            postSingeLoadEvent(syncData, EnumResult.UNKNOWN, false);
            // data null check
            if (data == null || data.getDataMap() == null || data.getSingleData(syncData) == null){
                resultFutureMap.put(syncData, async.submit(()-> EnumResult.NODATA));
                postSingeLoadEvent(syncData, EnumResult.NODATA, true);
                sync.submit(()-> { try { throw new NoSuchDataException(syncData.getID()); } catch (NoSuchDataException e) { e.printStackTrace(); } });
                // count +1, check all done
                finishCount.getAndIncrement();
                this.checkAllDone();
                continue;
            }
            //check permission
            if (checkPermission && !user.hasPermission(syncData.getPermissionNode())){
                resultFutureMap.put(syncData, async.submit(()-> EnumResult.NOPERMISSOON));
                postSingeLoadEvent(syncData, EnumResult.NOPERMISSOON, true);
                // count +1, check all done
                finishCount.getAndIncrement();
                this.checkAllDone();
            }else {
                if (syncData.shouldAsync()){
                    // should async
                    resultFutureMap.put(syncData, loadAsync(syncData));
                }else {
                    // should sync
                    resultFutureMap.put(syncData, loadSync(syncData));
                }
            }
        }
    }

    private Future<EnumResult> loadAsync(SyncData syncData){
        return async.submit(() -> {
            try {
                syncData.deserialize(user, data.getSingleData(syncData));
                postSingeLoadEvent(syncData, EnumResult.SUCCESS, true);
                // count +1, check all done
                finishCount.getAndIncrement();
                this.checkAllDone();
                return EnumResult.SUCCESS;
            }catch (DeserializeException e){
                e.printStackTrace();
                postSingeLoadEvent(syncData, EnumResult.FAIL, true);
                // count +1, check all done
                finishCount.getAndIncrement();
                this.checkAllDone();
                return EnumResult.FAIL;
            }
        });
    }

    private Future<EnumResult> loadSync(SyncData syncData){
        return sync.submit(()->{
            try {
                syncData.deserialize(user, data.getSingleData(syncData));
                postSingeLoadEvent(syncData, EnumResult.SUCCESS, true);
                // count +1, check all done
                finishCount.getAndIncrement();
                this.checkAllDone();
                return EnumResult.SUCCESS;
            }catch (DeserializeException e){
                e.printStackTrace();
                postSingeLoadEvent(syncData, EnumResult.FAIL, true);
                // check all done
                finishCount.getAndIncrement();
                this.checkAllDone();
                return EnumResult.FAIL;
            }
        });
    }

    public boolean isDone(){
        return finishCount.get() >= dataSize;
    }

    public Map<SyncData, EnumResult> getResultMap() {
        if (resultMap.size() == this.dataSize){
            return resultMap;
        }else {
            resultFutureMap.forEach((key, value) -> {
                try {
                    resultMap.put(key, value.get());
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                }
            });
        }
        return resultMap;
    }

    public boolean isAllSuccess(){
        for (EnumResult result:resultMap.values()){
            if (result != EnumResult.SUCCESS){
                return false;
            }
        }
        return true;
    }

    private void checkAllDone(){
        if (isDone() && !postAlready){
            sync.submit(()->{ new LoadAllEventImpl.Done(taskUUID, user, dataList, data, getResultMap(), source); });
            this.postAlready = true;
        }
    }

    public User getUser() {
        return user;
    }

    public UUID getTaskUUID() {
        return taskUUID;
    }

    private void postSingeLoadEvent(SyncData syncData, EnumResult result, boolean done){
        sync.submit(() -> {
            if (done){
                Utils.postEvent(new LoadSingleEvent.Done(taskUUID, user, data, syncData, result));
            }else {
                Utils.postEvent(new LoadSingleEvent.Pre(taskUUID, user, data, syncData));
            }
        });
    }


}
