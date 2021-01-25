package org.workflowsim.scheduling;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEntity;
import org.cloudbus.cloudsim.core.SimEvent;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.WorkflowSimTags;
import org.workflowsim.utils.ReplicaCatalog;

import utilpack.Tuple;
import utilpack.logging.Logger;
import utilpack.logging.Loggers;
import utilpack.random.RandomUtility;

public class WorkGivingSchedulingAlgorithm extends BaseSchedulingAlgorithm {

	private static Logger logger = Loggers.getLogger("WorkflowSim");
	private static Random random;
	private static Map<Integer, Node> nodes = new HashMap<>();
	private static List<Node> nodesList = new ArrayList<>();
	private static boolean initialized = false;

	private static Set<Task> assignedTasks = new HashSet<Task>();
	
	public static boolean workgivingActive = true;
	public static boolean replicationActive = true;

	public static int backupCounter = 0;
	public static int nonBackupCounter = 0;
	

	public WorkGivingSchedulingAlgorithm() {}

	@SuppressWarnings("unchecked")
	@Override
	public void run() throws Exception {
		
		if (random == null) {
			String seed = System.getProperty("simulation.seed");

			if (seed != null) {
				try {
					random = new Random(Long.parseLong(seed));
				} catch (NumberFormatException e) {
				}
			}

			if (random == null)
				random = new Random();
		}

		// create node structure for vms that does not have one
		if (!initialized) {
			initialized = true;

			for (CondorVM vm : (List<CondorVM>) getVmList()) {
				Node node = new Node(vm);
				nodes.put(vm.getId(), node);
				nodesList.add(node);
			}
		}

		// assign all tasks to a random node, if they are not already assigned
		for (Task task : (List<Task>) getCloudletList()) {
			if (!assignedTasks.contains(task)) {
				logger.info("job %s assigned", task.getCloudletId());
				assignedTasks.add(task);
			}
		}

		// for each node, poll tasks from queue and execute them
		for (Node node : nodes.values())
			node.executor(getCloudletList(), getScheduledList());

	}

	private static class Node {
		private CondorVM vm;

		private LinkedList<Task> readyQueue = new LinkedList<>();
		private Task runningTask = null;

		public Node(CondorVM vm) {
			super();
			this.vm = vm;
			new Validator();
			if(workgivingActive)
				new LoadBalancer();
		}

		public double getLoad() {
			double load = 0;
			for (Cloudlet cloudlet : readyQueue)
				load += cloudlet.getCloudletLength();/// vm.getMips();
			return load;
		}

		private void insertReadyTask(Task task) {
			task.bind(vm.getId());
			task.heartbeat();
			ListIterator<Task> it = readyQueue.listIterator();
			while (it.hasNext()) {
				Task ready = it.next();
				if (task.getPriority() > ready.getPriority()) {
					it.previous();
					break;
				}
			}
			it.add(task);
		}

		public void executor(List<Task> dmq, List<Task> sheduledCloudlets) {
			if (vm.getState() == WorkflowSimTags.VM_STATUS_IDLE) {

				if (readyQueue.isEmpty())
					decisionMaker(dmq);

				if (!readyQueue.isEmpty()) {
					logger.info("[node %d] polling a ready task", vm.getId());
					do {
						runningTask = readyQueue.poll();
					} while (runningTask.isRunning() && !readyQueue.isEmpty());

					if (runningTask.isRunning()) {
						logger.info("[node %d] no ready task present", vm.getId());
					} else {
						if(runningTask.isOwner(vm.getId()))
							nonBackupCounter++;
						else 
							backupCounter++;
						
						runningTask.setRunning(true);
						logger.info("[node %d] executing task %d", vm.getId(), runningTask.getCloudletId());
						runningTask.setVmId(vm.getId());
						sheduledCloudlets.add(runningTask);
						vm.setState(WorkflowSimTags.VM_STATUS_BUSY);
						for (FileItem file : runningTask.getFileList()) {
							if (!file.isRealInputFile(runningTask.getFileList()))
								ReplicaCatalog.addFileToStorage(file.getName(), "" + vm.getId());
						}
					}
				}
			}
		}

		public void decisionMaker_new(List<Task> dmq) {

			boolean taskFound = false;
			for (Task task : dmq) {
				if (!task.isFinished() && task.getVmId() == vm.getId() && task.heartBeatExpired()) {
					Node bestNode = getBestNode(task, nodes.values());
					double timeBest = dataTransferTime(task.getFileList(), bestNode.vm.getId());
					double timeLocal = dataTransferTime(task.getFileList(), vm.getId());
					if(timeBest < timeLocal)
						bestNode.insertReadyTask(task);
					else
						insertReadyTask(task);
					taskFound = true;
				}
			}

			if (!taskFound) {
				for (Task task : dmq) {
					if (!task.isFinished() && task.getVmId() != vm.getId() && task.heartBeatExpired()) {
						Node bestNode = getBestNode(task, nodes.values());
						if (bestNode != null)
							bestNode.insertReadyTask(task);
					}
				}
			}
		}
		
		public void decisionMaker(List<Task> dmq) {
			boolean taskFound = false;
			for (Task task : dmq) {
				if (!task.isFinished() && task.getVmId() == vm.getId() && task.heartBeatExpired()) {
					insertReadyTask(task);
					taskFound = true;
				}
			}

			if (!taskFound) {
				for (Task task : dmq) {
					if (!task.isFinished() && task.getVmId() != vm.getId() && task.heartBeatExpired()) {
						Node bestNode;
						if (task.getVmId() >= 0)
							bestNode = nodes.get(task.getVmId());
						else
							bestNode = getBestNode(task, nodes.values());
						if (bestNode != null)
							bestNode.insertReadyTask(task);
					}
				}
			}
		}

		private Node getBestNode(Task task, Collection<Node> nodes) {
			if (!task.isBindable())
				return null;

			Optional<Tuple> closest = nodes.parallelStream().filter(node -> !task.isBound(node.vm.getId()))
					.map(node -> {
						CondorVM vm = node.vm;
						double time = dataTransferTime(task.getFileList(), vm.getId()) + node.getLoad();
						return Tuple.ofObjects(node, time);
					}).min((t1, t2) -> {
						double time1 = t1.get(1);
						double time2 = t2.get(1);
						return time1 < time2 ? -1 : time1 > time2 ? 1 : 0;
					});

			if (closest.isEmpty())
				return null;

			return closest.get().get(0);
		}

		protected static double dataTransferTime(List<FileItem> requiredFiles, int vmId) {
			double time = 0.0;

			for (FileItem file : requiredFiles) {
				// The input file is not an output File
				if (file.isRealInputFile(requiredFiles)) {
					List<String> siteList = ReplicaCatalog.getStorageList(file.getName());

					boolean hasFile = false;
					for (String site : siteList) {
						if (site.equals(Integer.toString(vmId))) {
							hasFile = true;
							break;
						}
					}
					if (!hasFile)
						time += file.getSize();
				}
			}
			return time;
		}

		private class LoadBalancer extends SimEntity {
			int finishDetection = 0;

			public LoadBalancer() {
				super("LoadBalancer@" + vm.getId());
			}

			@Override
			public void startEntity() {
				logger.info("%s entity started", getName());
				send(getId(), lbInterval, CloudSimTags.CLOUDLET_STATUS);
			}

			long lbInterval = 1;

			@Override
			public void processEvent(SimEvent ev) {
				boolean lbSuccess = false;
				if (readyQueue.size() > 1) {
					// logger.info("[node %d] invoking Load Balancer", vm.getId());

					// select a random neighborhood
					List<Node> neighbourhood = new LinkedList<Node>();
					Set<Integer> indices = RandomUtility.randomIntegers(0, nodesList.size() - 1,
							(int) Math.sqrt(nodesList.size()), random);

					// logger.info("[node %d] Load Balancer selected ramdom indices for
					// neighbourhood: %s", vm.getId(), indices);
					for (Integer index : indices)
						neighbourhood.add(nodesList.get(index));
					neighbourhood.remove(Node.this);

					// logger.info("[node %d] Load Balancer checking overload respect to
					// neighbourhood", vm.getId());

					if (isMostOverloaded(neighbourhood)) {
						Node neighbour = getLessOverloaded(neighbourhood);
						if (neighbour != null) {
							lbSuccess = balanceLoad(neighbour);
						}
					}
				}

				if (!lbSuccess && lbInterval < 120) {
					lbInterval *= 2;
				}

				// logger.info("[node %d] Load Balancer awaiting for %d", vm.getId(),
				// lbInterval);

				boolean proactivity = true;
				if (assignedTasks.stream().allMatch(t -> t.isFinished())) {
					finishDetection++;
					if (finishDetection >= 10)
						proactivity = false;
				} else {
					finishDetection = 0;
				}

				if (proactivity)
					send(getId(), lbInterval, CloudSimTags.CLOUDLET_STATUS);
				else
					logger.info("[node %d] Load Balancer terminated", vm.getId());
			}
			
			public boolean balanceLoad(Node neighbour) {
				List<Task> tasks;
				if(!replicationActive) {
					tasks = selectHalfTasksNoReplication(neighbour);
				} else {
					tasks = selectHalfTasks(neighbour);
				}
				if (!tasks.isEmpty()) {
					addTasksToQueue(tasks, neighbour);
					lbInterval = 1;
					logger.info("[node %d] Load Balancer sent to node %d the following jobs: %s",
							vm.getId(), neighbour.vm.getId(), tasks);
					return true;
				}
				return false;
			}

			public boolean isMostOverloaded(List<Node> neighbourhood) {
				for (Node neighbour : neighbourhood)
					if (neighbour.getLoad() > getLoad())
						return false;
				return true;
			}

			public Node getLessOverloaded(List<Node> neighbourhood) {
				Node minNode = null;
				for (Node node : neighbourhood)
					if (minNode == null || node.getLoad() < minNode.getLoad())
						minNode = node;
				return minNode;
			}

			public List<Task> selectHalfTasksNoReplication(Node neighbour) {
				final int nMax = readyQueue.size() / 2;
				List<Task> tasks = new ArrayList<>(nMax);
				int selectedTasks = 0;

				Iterator<Task> it = readyQueue.descendingIterator();
				while (it.hasNext()) {
					Task task = it.next();
					if (task.isOwner(vm.getId()) || task.isOwner(neighbour.vm.getId())) {
						it.remove();
						tasks.add(task);
						selectedTasks++;
						if (selectedTasks >= nMax)
							break;
					}
				}
				return tasks;
			}

			public List<Task> selectHalfTasks(Node neighbour) {
				final int nMax = readyQueue.size() / 2;
				List<Task> tasks = new ArrayList<>(nMax);
				int selectedTasks = 0;

				Iterator<Task> it = readyQueue.descendingIterator();
				while (it.hasNext()) {
					Task task = it.next();
					if (task.isOwner(vm.getId()) && !task.isBound(neighbour.vm.getId()) && task.isBindable()) {
						tasks.add(task);
						selectedTasks++;
						if (selectedTasks >= nMax)
							break;
					}
				}
				return tasks;
			}

			/*
			 * public List<Task> selectHalfBindableTasks(){ final int nMax =
			 * readyQueue.size()/2; List<Task> tasks = new ArrayList<>(nMax); int
			 * selectedTasks = 0;
			 * 
			 * Iterator<Task> it = readyQueue.descendingIterator(); while(it.hasNext()) {
			 * Task task = it.next(); if(task.isBindable()) { tasks.add(task);
			 * selectedTasks++; if(selectedTasks >= nMax) break; } } return tasks; }
			 */

			public void addTasksToQueue(List<Task> tasks, Node node) {
				for (Task task : tasks)
					node.insertReadyTask(task);
			}

			@Override
			public void shutdownEntity() {
				logger.info("%s entity shutdown", getName());
			}

		}

		private class Validator extends SimEntity {
			int finishDetection;

			public Validator() {
				super("Validator@" + vm.getId());
			}

			@Override
			public void startEntity() {
				logger.info("%s entity started", getName());
				send(getId(), validatorInterval, CloudSimTags.CLOUDLET_STATUS);
			}

			double validatorInterval = Task.HEART_BEAT_EXPIRATION_PERIOD / 2;

			@Override
			public void processEvent(SimEvent ev) {
				if (runningTask != null)
					runningTask.heartbeat();
				Iterator<Task> it = readyQueue.iterator();
				while (it.hasNext()) {
					Task task = it.next();
					if (task.isFinished())
						it.remove();
					else
						task.heartbeat();
				}

				// System.out.println(assignedTasks.parallelStream().filter(t->!t.isFinished()).collect(Collectors.toSet()));

				boolean proactivity = true;
				if (assignedTasks.stream().allMatch(t -> t.isFinished())) {
					finishDetection++;
					if (finishDetection >= 10)
						proactivity = false;
				} else {
					finishDetection = 0;
				}

				if (proactivity)
					send(getId(), validatorInterval, CloudSimTags.CLOUDLET_STATUS);
			}

			@Override
			public void shutdownEntity() {
				logger.info("%s entity shutdown", getName());
			}

		}
	}

}
