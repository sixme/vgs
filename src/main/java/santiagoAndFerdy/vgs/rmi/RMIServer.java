package santiagoAndFerdy.vgs.rmi;

import java.net.MalformedURLException;
import java.rmi.Naming;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.server.UnicastRemoteObject;

/**
 * Created by Fydio on 3/18/16.
 */
public class RMIServer {
    public RMIServer() {
        System.out.println("Start server");

        try {
            LocateRegistry.createRegistry(1099);
            System.out.println("Registery created");
        } catch (RemoteException e) {
            System.out.println("Registery already exsists");
        }
    }

    public void register(String name, UnicastRemoteObject impl) throws MalformedURLException, RemoteException {
        Naming.rebind("//localhost/" + name, impl);
        System.out.println("Registered object at" + name);
    }
}