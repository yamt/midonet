/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.layer3;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.ReplicatedSet;
import com.midokura.midolman.util.Callback1;
import com.midokura.midolman.util.JSONSerializer;

public class ReplicatedRoutingTable {

    private static final Logger log = LoggerFactory.getLogger(ReplicatedRoutingTable.class);

    private class ReplicatedRouteSet extends ReplicatedSet<Route> {

        JSONSerializer<Route> serializer = new JSONSerializer<Route>();

        public ReplicatedRouteSet(Directory d, CreateMode mode) {
            super(d, mode);
        }

        protected String encode(Route rt) {
            //TODO(dmd): this is slightly ghetto
            try {
                return new String(serializer.objToBytes(rt));
            } catch (IOException e) {
                log.warn("ReplicatedRouteSet.encode", e);
                return null;
            }
        }

        protected Route decode(String str) {
            try {
                return serializer.bytesToObj(str.getBytes(), Route.class);
            } catch (Exception e) {
                log.warn("ReplicatedRouteSet.decode", e);
                return null;
            }
        }
    };

    private class MyWatcher implements ReplicatedSet.Watcher<Route> {

        @Override
        public void process(Collection<Route> added, Collection<Route> removed) {
            for (Route rt : removed) {
                table.deleteRoute(rt);
            }
            for (Route rt : added) {
                table.addRoute(rt);
            }
            if (added.size() > 0 || removed.size() > 0)
                notifyWatchers();
        }
    }

    private RoutingTable table;
    private UUID routerId;
    private ReplicatedRouteSet routeSet;
    private Set<Callback1<UUID>> watchers;

    public ReplicatedRoutingTable(UUID routerId, Directory routeDir,
            CreateMode createMode) {
        this.routerId = routerId;
        table = new RoutingTable();
        watchers = new HashSet<Callback1<UUID>>();
        routeSet = new ReplicatedRouteSet(routeDir, createMode);
        routeSet.addWatcher(new MyWatcher());
    }

    public void start() {
        routeSet.start();
    }

    public void stop() {
        routeSet.stop();
    }

    public void addWatcher(Callback1<UUID> watcher) {
        watchers.add(watcher);
    }

    public void removeWatcher(Callback1<UUID> watcher) {
        watchers.remove(watcher);
    }

    private void notifyWatchers() {
        // TODO(pino): should these be called asynchronously?
        for (Callback1<UUID> watcher : watchers) {
            watcher.call(routerId);
        }
    }

    public void addRoute(Route rt) throws KeeperException, InterruptedException {
        routeSet.add(rt);
    }

    public void deleteRoute(Route rt) throws KeeperException {
        routeSet.remove(rt);
    }

    public List<Route> lookup(int src, int dst) {
        return table.lookup(src, dst);
    }
}
