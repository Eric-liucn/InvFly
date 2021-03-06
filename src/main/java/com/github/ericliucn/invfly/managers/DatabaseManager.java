package com.github.ericliucn.invfly.managers;

import com.github.ericliucn.invfly.Invfly;
import com.github.ericliucn.invfly.api.SyncData;
import com.github.ericliucn.invfly.config.InvFlyConfig;
import com.github.ericliucn.invfly.data.EnumResult;
import com.github.ericliucn.invfly.data.StorageData;
import com.github.ericliucn.invfly.event.SaveAllEventImpl;
import com.github.ericliucn.invfly.utils.Utils;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.commons.io.IOUtils;
import org.spongepowered.api.command.CommandSource;
import org.spongepowered.api.entity.living.player.User;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class DatabaseManager {

    private final String table;
    private static HikariConfig config;
    private final InvFlyConfig invConfig;
    private HikariDataSource dataSource;

    public DatabaseManager(){
        this.invConfig = Invfly.instance.getConfigLoader().getConfig();
        this.setConfig();
        this.setDataSource();
        this.table = invConfig.storage.basic.tableName;
        this.createTable();
    }

    public void setConfig() {
        config = new HikariConfig(){{
            setMaxLifetime(invConfig.storage.connectionPool.maxLifeTime);
            setMaximumPoolSize(invConfig.storage.connectionPool.maxPoolSize);
            setMinimumIdle(invConfig.storage.connectionPool.minIdleSize);
            setIdleTimeout(invConfig.storage.connectionPool.idleTimeout);
            setConnectionTimeout(invConfig.storage.connectionPool.connectionTimeout);
            setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s?useSSL=false",
                    invConfig.storage.basic.host,
                    invConfig.storage.basic.port,
                    invConfig.storage.basic.database));
            setUsername(invConfig.storage.basic.username);
            setPassword(invConfig.storage.basic.password);
        }};
    }

    private void setDataSource() {
        dataSource = new HikariDataSource(config);
    }

    private DataSource getDataSource(){
        if (dataSource == null || dataSource.isClosed()){
            this.setDataSource();
        }
        return dataSource;
    }

    private void createTable(){
        String sql = String.format("create table if not exists %s " +
                "(id int not null auto_increment, " +
                "uuid varchar(40) not null, " +
                "name text, " +
                "data longblob, " +
                "time timestamp(5) default current_timestamp(5), " +
                "disconnect boolean default 0," +
                "server text," +
                "index(time)," +
                "index(uuid(40))," +
                "primary key(id))Engine=InnoDB default charset=utf8mb4", table);
        try (
                Connection connection = getDataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
        ){
            statement.execute();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void saveData(StorageData data, User user, UUID taskUUID, List<SyncData> dataList, Map<SyncData, EnumResult> resultMap, CommandSource source){
        String sql = String.format("insert into %s (uuid, name, data, disconnect, server) values('%s', '%s', ?, %b, '%s')",
                table, data.getUuid(), data.getName(), data.isDisconnect(), data.getServerName());
        try (
                Connection connection = getDataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ){
            statement.setBinaryStream(1, toInputStream(data.getData()));
            statement.execute();
            Utils.postEvent(new SaveAllEventImpl.Done(user, taskUUID, dataList, data, resultMap, source));
        }catch (SQLException e){
            e.printStackTrace();
        }
    }


    @Nullable
    public StorageData getLatest(User user){
        String sql = String.format("select t1.* from %s t1 where t1.time = (select max(t2.time) from %s t2 where t2.uuid = '%s')",
                table, table, user.getUniqueId());
        try (
                Connection connection = getDataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()
                ){
            while (resultSet.next()){
                return new StorageData(
                        resultSet.getInt("id"),
                        resultSet.getString("uuid"),
                        resultSet.getString("name"),
                        getStringFromStream(resultSet.getBinaryStream("data")),
                        resultSet.getTimestamp("time"),
                        resultSet.getBoolean("disconnect"),
                        resultSet.getString("server")
                );
            }
        }catch (SQLException | IOException e){
            e.printStackTrace();
        }
        return null;
    }

    @Nonnull
    public List<StorageData> getAllData(User user, Duration duration){
        List<StorageData> dataList = new ArrayList<>();
        long seconds = duration.getSeconds();
        String sql = String.format("select * from %s where uuid = '%s' && time > (now() - interval %d second) " +
                "order by time desc", table, user.getUniqueId().toString(), seconds);
        try (
                Connection connection = getDataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()
        ){
            while (resultSet.next()){
                dataList.add(new StorageData(
                        resultSet.getInt("id"),
                        resultSet.getString("uuid"),
                        resultSet.getString("name"),
                        getStringFromStream(resultSet.getBinaryStream("data")),
                        resultSet.getTimestamp("time"),
                        resultSet.getBoolean("disconnect"),
                        resultSet.getString("server")
                ));
            }
        }catch (SQLException | IOException e){
            e.printStackTrace();
        }
        return dataList;
    }


    public void deleteOutDateUser(User user, Duration duration){
        long seconds =  duration.getSeconds();
        String sql = String.format("delete from %s where time < (now() - interval %d second) && uuid = '%s'",
                table,seconds, user.getUniqueId().toString());
        try (
                Connection connection = getDataSource().getConnection();
                PreparedStatement preparedStatement = connection.prepareStatement(sql)
                ){
            preparedStatement.execute();
        }catch (SQLException e){
            e.printStackTrace();
        }
    }

    public void deleteOutDate(Duration duration){
        long seconds =  duration.getSeconds();
        String sql = String.format("delete from %s where time < (now() - interval %d second)",
                table,seconds);
        try (
                Connection connection = getDataSource().getConnection();
                PreparedStatement preparedStatement = connection.prepareStatement(sql)
        ){
            preparedStatement.execute();
        }catch (SQLException e){
            e.printStackTrace();
        }
    }

    public void deleteRecord(int id){
        String sql = String.format("delete from %s where id = %d", table, id);
        try (
                Connection connection = getDataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)
                ){
            statement.execute();
        }catch (SQLException e){
            e.printStackTrace();
        }

    }

    public boolean isDataExists(UUID uuid){
        String sql = String.format("select exists(select * from %s where uuid = '%s')", table, uuid.toString());
        try (
                Connection connection = getDataSource().getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet resultSet = statement.executeQuery()
                ){
            while (resultSet.next()){
                return resultSet.getBoolean(1);
            }
        }catch (SQLException e){
            e.printStackTrace();
        }
        return false;
    }

    private static InputStream toInputStream(String data){
        return IOUtils.toInputStream(data, StandardCharsets.UTF_8);
    }

    private static String getStringFromStream(InputStream stream) throws IOException {
        return IOUtils.toString(stream, StandardCharsets.UTF_8);
    }

}
