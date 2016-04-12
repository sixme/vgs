package santiagoAndFerdy.vgs.gridScheduler;

import santiagoAndFerdy.vgs.discovery.IRepository;
import santiagoAndFerdy.vgs.discovery.Pinger;
import santiagoAndFerdy.vgs.discovery.Status;
import santiagoAndFerdy.vgs.discovery.selector.Selectors;
import santiagoAndFerdy.vgs.messages.*;
import santiagoAndFerdy.vgs.resourceManager.IResourceManager;
import santiagoAndFerdy.vgs.rmi.RmiServer;

import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class GridScheduler extends UnicastRemoteObject implements IGridScheduler {
    private static final long              serialVersionUID = -5694724140595312739L;

    private RmiServer                      rmiServer;
    private int                            id;
    private IRepository<IResourceManager>  rmRepository;
    private IRepository<IGridScheduler>    gsRepository;
    private Pinger<IResourceManager>       resourceManagerPinger;
    private Pinger<IGridScheduler>         gridSchedulerPinger;

    private Map<Integer, Set<WorkRequest>> monitoredJobs;
    private Map<Integer, Set<WorkRequest>> backUpJobs;

    private Boolean                        recovering;

    private boolean                        running;

    private long                           load;
    private Logger                         logger;
    private FileHandler                    fh;

    public GridScheduler(RmiServer rmiServer, int id, IRepository<IResourceManager> rmRepository, IRepository<IGridScheduler> gsRepository)
            throws RemoteException {
        this.rmiServer = rmiServer;
        this.id = id;
        rmiServer.register(gsRepository.getUrl(id), this);

        this.rmRepository = rmRepository;
        this.gsRepository = gsRepository;
        gridSchedulerPinger = new Pinger(gsRepository);
        resourceManagerPinger = new Pinger(rmRepository);
        logger = Logger.getLogger("GridScheduler" + id);

        try {
            fh = new FileHandler("GridScheduler" + id + ".log");
        } catch (Exception e) {
            e.printStackTrace();
        }

        logger.addHandler(fh);
        SimpleFormatter formatter = new SimpleFormatter();
        fh.setFormatter(formatter);
        start();
        setUpReSchedule();
        setUpSelfPromote();
    }

    @Override
    public synchronized void monitor(MonitoringRequest monitorRequest) throws RemoteException {
        if (!running)
            throw new RemoteException("I am offline");

        logger.info("[GS\t" + id + "] Received job " + monitorRequest.getToMonitor().getJob().getJobId());
        monitoredJobs.get(monitorRequest.getSourceResourceManagerId()).add(monitorRequest.getToMonitor());
    }

    @Override
    public void backUp(BackUpRequest backUpRequest) throws RemoteException {
        if (!running)
            throw new RemoteException("I am offline");

        logger.info("[GS\t" + id + "] Received backup request from " + backUpRequest.getSourceResourceManagerId());
        backUpJobs.get(backUpRequest.getSourceResourceManagerId()).add(backUpRequest.getToBackUp());
    }

    @Override
    public void promote(PromotionRequest promotionRequest) throws RemoteException {
        if (!running)
            throw new RemoteException("I am offline");

        logger.info("[GS\t" + id + "] Promoting to primary for job " + promotionRequest.getToBecomePrimaryFor().getJob().getJobId());
        backUpJobs.get(promotionRequest.getSourceResourceManagerId()).remove(promotionRequest.getToBecomePrimaryFor());
        monitoredJobs.get(promotionRequest.getSourceResourceManagerId()).add(promotionRequest.getToBecomePrimaryFor());

    }

    @Override
    public synchronized void offLoad(WorkRequest req) throws RemoteException {
        if (!running)
            throw new RemoteException("I am offline");
        int exRm = req.getJob().getCurrentResourceManagerId();
        Optional<Integer> newRm = rmRepository.invokeOnEntity((rm, rmId) -> {
            logger.info("[GS\t" + id + "] Sending to RM " + rmId + " job " + req.getJob().getJobId());
            WorkOrder wO = new WorkOrder(id, req);
            rm.orderWork(wO);
            return rmId;
        } , Selectors.invertedWeighedRandom, exRm);
        if (newRm.isPresent()) {
            int rmId = newRm.get();
            Set<WorkRequest> q;
            if (monitoredJobs.containsKey(rmId)) {
                q = monitoredJobs.get(rmId);
            } else {
                q = new HashSet<>();
            }
            q.add(req);
            monitoredJobs.put(rmId, q);
        } else {
            logger.severe("[GS\t" + id + "] There was a problem allocating the job");
        }

    }

    @Override
    public void releaseMonitored(WorkRequest request) throws RemoteException {
        if (!running)
            throw new RemoteException("I am offline");

        monitoredJobs.remove(request);
        logger.info("[GS\t" + id + "] Stop monitoring " + request.getJob().getJobId());
    }

    @Override
    public void releaseBackUp(WorkRequest workRequest) throws RemoteException {
        if (!running)
            throw new RemoteException("I am offline");

        backUpJobs.remove(workRequest);
        logger.info("[GS\t" + id + "] Releasing back-up of workRequest " + workRequest.getJob().getJobId());
    }

    @Override
    public void start() throws RemoteException {
        running = true;
        monitoredJobs = new HashMap<>();
        backUpJobs = new HashMap<>();

        recovering = false;

        for (int rmId : rmRepository.ids()) {
            monitoredJobs.put(rmId, new HashSet<>());
            backUpJobs.put(rmId, new HashSet<>());
            rmRepository.getEntity(rmId).ifPresent(rm -> {
                try {
                    rm.receiveGridSchedulerWakeUpAnnouncement(id);
                } catch (RemoteException e) {
                }
            });
        }

        for (int gsId : gsRepository.idsExcept(id)) {
            gsRepository.getEntity(gsId).ifPresent(gs -> {
                try {
                    gs.receiveGridSchedulerWakeUpAnnouncement(id);
                } catch (RemoteException e) {
                    // Can be offline. that's okay.
                }
            });
        }

        load = 1;

        gridSchedulerPinger.start();
        resourceManagerPinger.start();

        logger.info("[GS\t" + id + "] Online");
    }

    @Override
    public void shutDown() throws RemoteException {
        running = false;
        monitoredJobs = null;
        backUpJobs = null;

        gridSchedulerPinger.stop();
        resourceManagerPinger.stop();

        logger.info("[GS\t" + id + "] Offline");
    }

    public void setUpReSchedule() {
        rmRepository.onOffline(rmId -> {
            if (running && !recovering) {
                synchronized (recovering) {
                    recovering = true;
                }
                monitoredJobs.get(rmId).forEach(monitored -> {

                    WorkOrder reScheduleOrder = new WorkOrder(id, monitored);

                    rmRepository.invokeOnEntity((rm, newRmId) -> {
                        logger.info("[GS\t" + id + "] Rescheduling job " + monitored.getJob().getJobId() + " on RM " + newRmId);
                        rm.orderWork(reScheduleOrder);

                        return null;
                    } , Selectors.invertedWeighedRandom);
                });
                synchronized (recovering) {
                    recovering = true;
                }
            }

            return null;
        });
    }

    public void setUpSelfPromote() {

    }

    @Override
    public void receiveResourceManagerWakeUpAnnouncement(int from) throws RemoteException {
        if (!running)
            throw new RemoteException("I am offline");

        logger.info("[GS\t" + id + "] RM " + from + " awake");
        rmRepository.setLastKnownStatus(from, Status.ONLINE);
    }

    @Override
    public void receiveGridSchedulerWakeUpAnnouncement(int from) throws RemoteException {
        if (!running)
            throw new RemoteException("I am offline");

        logger.info("[GS\t" + id + "] GS " + from + " awake");
        gsRepository.setLastKnownStatus(from, Status.ONLINE);
    }

    @Override
    public long ping() throws RemoteException {
        if (!running)
            throw new RemoteException("I am offline");

        return load;
    }

    @Override
    public int getId() throws RemoteException {
        if (!running)
            throw new RemoteException("I am offline");

        return id;
    }
}