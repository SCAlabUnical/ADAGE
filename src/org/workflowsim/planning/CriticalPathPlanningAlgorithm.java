package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.workflowsim.ClusterRamDiskStorage;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Task;

import utilpack.TableView;
import utilpack.logging.Logger;
import utilpack.logging.Loggers;

public class CriticalPathPlanningAlgorithm extends BasePlanningAlgorithm {

	private static Logger logger = Loggers.getLogger("WorkflowSim");
	private ArrayList<LinkedList<Task>> VmTasks;
	private ArrayList<HashSet<FileItem>> VmFiles;
	private ArrayList<Double[]> VmReadyTimes; // {TaskReady, DataReady}

	private HashSet<Task> initials(List<Task> list) {
		HashSet<Task> remaining = new HashSet<>(list);
		for (Task t : list) {
			for (Task td : t.getChildList()) {
				remaining.remove(td);
			}
		}

		logger.info("Critical path initial nodes %s", remaining);
		return remaining;
	}

	private void maxDur(List<Task> list) {
		long max = -1;
		for (Task t : list) {
			if (t.getCriticalPath() > max) {
				max = t.getCriticalPath();
			}
		}
		Task.setMaxDur(max);
		logger.info("Critical path length (cost): %s", Task.getMaxDur());
		for (Task t : list) {
			t.setLatest();
		}
	}

	private void calcEarly(HashSet<Task> initials) {
		for (Task initial : initials) {
			initial.setEarlyStart(0);
			initial.setEarlyFinish(initial.getCloudletLength());
			setEarly(initial);
		}
	}

	private void setEarly(Task initial) {
		long completionTime = initial.getEarlyFinish();
		for (Task t : initial.getChildList()) {
			if (completionTime >= t.getEarlyStart()) {
				t.setEarlyStart(completionTime);
				t.setEarlyFinish(completionTime + t.getCloudletLength());
			}
			setEarly(t);
		}
	}

	private Task[] criticalPathAlgo(List<Task> list) {
		HashSet<Task> completedCrit = new HashSet<>();
		HashSet<Task> remainingCrit = new HashSet<>(list);

		while (!remainingCrit.isEmpty()) {
			boolean progress = false;

			for (Iterator<Task> it = remainingCrit.iterator(); it.hasNext();) {
				Task task = it.next();
				if (completedCrit.containsAll(task.getChildList())) {
					//System.out.println("criticalPathAlgo");
					long critical = 0;
					for (Task t : task.getChildList()) {
						if (t.getCriticalPath() > critical) {
							critical = t.getCriticalPath();
						}
					}
					task.setCriticalPath(critical + task.getCloudletLength());// CloudletLength = million instructions
					completedCrit.add(task);
					it.remove();
					progress = true;
				}
			}
			
			if (!progress) {
				throw new RuntimeException("Cyclic dependency, algorithm stopped");
			}
		}
		// get the cost
		maxDur(list);
		HashSet<Task> initialNodes = initials(list);
		calcEarly(initialNodes);

		// get the tasks
		Task[] ret = completedCrit.toArray(new Task[0]);

		Arrays.sort(ret, (Comparator<? super Task>) (Task o1, Task o2) -> {
			return Integer.signum((int) (o1.getLatestStart() - (o2.getLatestStart())));
		});

		return ret;
	}

	private void print(Task[] tasks) {
		TableView tableView = new TableView();
		tableView.row("Task", "ES", "EF", "LS", "LF", "Floats", "Critical?", "Type");
//		String format = "%1$-10s %2$-5s %3$-5s %4$-5s %5$-5s %6$-5s %7$-10s %8$-10s\n";
//		System.out.format(format, "Task", "ES", "EF", "LS", "LF", "Floats", "Critical?", "Type");
		for (Task t : tasks) {
			tableView.row((Object[]) t.toStringArray());
			//System.out.format(format, (Object[]) t.toStringArray());
		}
		logger.info(tableView.toString());
	}
	
	private static Map<Integer, Integer> countByVm = new HashMap<>();

	@Override
	public void run() {
		init();
		Task[] priorityList = criticalPathAlgo(getTaskList());
		for (int i = 0; i < priorityList.length; i++) {
			schedule(priorityList[i]);
		}
		getTaskList().clear();
		for (Task task : priorityList) {
			getTaskList().add(task);
		}
		print(priorityList);
		setFilePriority();
		//System.out.println("vm=task-count");
		//System.out.println(countByVm);
	}

	private void schedule(Task task) {
		int id = selectVm(task);
        countByVm.compute(id, (k,v)->v==null? 1 : v+1);
		VmTasks.get(id).add(task);
		VmFiles.get(id).addAll(task.getFileList());
		task.setUserId(getVmList().get(id).getUserId());
		task.setVmId(id);
	}

	private int selectVm(Task task) {
		List<FileItem> fileList = task.getFileList();
		int idMin = 0;
		double minTime = -1;
		double executionTime;
		double transfertTime;
		double newTransfertTime = 0;
		double time;
		for (int i = 0; i < getVmList().size(); i++) {
			executionTime = VmReadyTimes.get(i)[0];
			transfertTime = VmReadyTimes.get(i)[1];
			for (FileItem fileItem : fileList) {
				if (!VmFiles.get(i).contains(fileItem) && fileItem.isRealInputFile(fileList))
					transfertTime += fileItem.getSize() / getVmList().get(i).getBw();
			}
			time = Math.max(transfertTime, executionTime);
			if (time < minTime || minTime == -1) {
				minTime = time;
				idMin = i;
				newTransfertTime = transfertTime;
			}
		}
		VmReadyTimes.get(idMin)[0] = minTime + task.getCloudletLength() / getVmList().get(idMin).getMips();
		VmReadyTimes.get(idMin)[1] = newTransfertTime;
		return idMin;
	}

	private void init() {
		VmTasks = new ArrayList<>();
		VmFiles = new ArrayList<>();
		VmReadyTimes = new ArrayList<>();
		for (int i = 0; i < getVmList().size(); i++) {
			VmTasks.add(i, new LinkedList<>());
			VmFiles.add(i, new HashSet<>());
			VmReadyTimes.add(i, new Double[] { 0.0, 0.0 });
		}

	}

	private class UtilizzoFile implements Comparable<UtilizzoFile> {
		private FileItem file;
		private int utilizzi = 0;

		public UtilizzoFile(FileItem f) {
			this.file = f;
		}

		private UtilizzoFile incrementaUtilizzi() {
			utilizzi++;
			return this;
		}

		@Override
		public int compareTo(UtilizzoFile arg0) {
			return arg0.utilizzi - this.utilizzi;
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof UtilizzoFile))
				return false;
			UtilizzoFile uf = (UtilizzoFile) o;
			return file.equals(uf.file);
		}
	}

	private void setFilePriority() {
		ArrayList<UtilizzoFile> utilizzi;
		double size;
		for (int i = 0; i < getVmList().size(); i++) {
			utilizzi = new ArrayList<>();
			for (FileItem fileItem : VmFiles.get(i)) {
				utilizzi.add(new UtilizzoFile(fileItem));
			}
			for (Task task : VmTasks.get(i)) {
				for (FileItem file : task.getFileList()) {
					utilizzi.add(utilizzi.remove(utilizzi.indexOf(new UtilizzoFile(file))).incrementaUtilizzi());
				}
			}
			Collections.sort(utilizzi);
			size = 0;
			for (UtilizzoFile utilizzoFile : utilizzi) {
				double fileSize = utilizzoFile.file.getSize();
				CondorVM vm = (CondorVM) getVmList().get(i);
				if (size + fileSize < ClusterRamDiskStorage.getCapacity("vm_" + vm.getId()) * 2) {
					size += fileSize;
					utilizzoFile.file.setPriority(true);
				}
			}
		}
	}

}
