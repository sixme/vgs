package santiagoAndFerdy.vgs.resourceManager;

import com.sun.istack.internal.NotNull;
import santiagoAndFerdy.vgs.messages.WorkRequest;

import java.net.MalformedURLException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Created by Fydio on 3/19/16.
 */
public class Node {
    private int id;
    private WorkRequest current;
    private IResourceManager resourceManager;
    private ScheduledExecutorService timer;

    public Node(int id, @NotNull IResourceManager rm, ScheduledExecutorService timer) {
        this.id = id;
        this.resourceManager = rm;
        this.timer = timer;
    }

    public int getId() {
        return id;
    }

    public synchronized void handle(@NotNull WorkRequest toExecute) {
        this.current = toExecute;
       // Thread.sleep(toExecute.getJob().getDuration());
       // this.resourceManager.finish(this, toExecute);
        this.timer.schedule(() -> {
                    try {
                        this.resourceManager.finish(this, toExecute);
                        // TODO IResourceManagerRemote
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                },
                toExecute.getJob().getDuration(), TimeUnit.MILLISECONDS);
    }

    public void setIdle() {
        this.current = null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Node)) return false;

        Node node = (Node) o;

        if (id != node.id) return false;
        return resourceManager.equals(node.resourceManager);

    }

    @Override
    public int hashCode() {
        int result = id;
        result = 31 * result + resourceManager.hashCode();
        return result;
    }
}