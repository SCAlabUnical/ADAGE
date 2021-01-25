/**
 * Copyright 2012-2013 University Of Southern California
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.workflowsim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Consts;
import org.cloudbus.cloudsim.UtilizationModelFull;
import org.cloudbus.cloudsim.core.CloudSim;
import org.workflowsim.scheduling.WorkGivingSchedulingAlgorithm;

/**
 * Task is an extention to Cloudlet in CloudSim. It supports the implementation
 * of dependencies between tasks, which includes a list of parent tasks and a
 * list of child tasks that it has. In WorkflowSim, the Workflow Engine assure
 * that a task is released to the scheduler (ready to run) when all of its
 * parent tasks have completed successfully
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class Task extends Cloudlet {
	public static final double HEART_BEAT_EXPIRATION_PERIOD = 125;
	
	public static int maxNumBackup = 2;

	private static long maxDur;

	private long earlyStart;

	private long earlyFinish;

	private long latestStart;

	private long latestFinish;

	private long criticalPath;
	
	// New Scheduler properties
	private double heartBeat;
	private double startPeriodExpiration = CloudSim.clock() + HEART_BEAT_EXPIRATION_PERIOD;
	private Set<Integer> boundNodes = new TreeSet<Integer>();
	private int numBackup = Math.max(0, maxNumBackup);

	public boolean startPeriodExpired() {
		return startPeriodExpiration < CloudSim.clock();
	}
	
	public boolean heartBeatExpired() {
		return heartBeat < CloudSim.clock();
	}
	
	public void heartbeat() {
		heartBeat = CloudSim.clock() + HEART_BEAT_EXPIRATION_PERIOD;
	}
	
	public boolean hasBeenPolledByOwner() {
		return heartBeat > 0;
	}
	
	public boolean bind(Integer nodeId) {
		if(getVmId() >= 0 && !isBound(getVmId()))
			boundNodes.add(getVmId());
		
		if(isBound(nodeId))
			return true;
		
		if(isBindable()) {
			boundNodes.add(nodeId);
			return true;
		}
		
		return false;
	}
	
	public boolean isBindable() {
		return boundNodes.size() < numBackup+1;
	}
	
	public boolean isBound(Integer nodeId) {
		return boundNodes.contains(nodeId);
	}
	
	public boolean isOwner(int nodeId) {
		return nodeId == getVmId();
	}
	
	public Set<Integer> getBoundNodes(){
		return boundNodes;
	}
	boolean running = false;
	public boolean isRunning() {
		return running;
	}
	
	public void setRunning(boolean running) {
		this.running = running;
	}
	
	public void setLatest() {
		latestStart = maxDur - criticalPath;
		latestFinish = latestStart + getCloudletLength();
	}

	public String[] toStringArray() {
		String criticalCond = earlyStart == latestStart ? "Yes" : "No";
		String[] toString = { "" + getCloudletId(), earlyStart + 1 + "", earlyFinish + "", latestStart + 1 + "",
				latestFinish + "", latestStart - earlyStart + "", criticalCond , getType()};
		return toString;
	}

	public static long getMaxDur() {
		return maxDur;
	}

	public long getEarlyStart() {
		return earlyStart;
	}

	public long getEarlyFinish() {
		return earlyFinish;
	}

	public long getLatestStart() {
		return latestStart;
	}

	public long getLatestFinish() {
		return latestFinish;
	}

	public long getCriticalPath() {
		return criticalPath;
	}

	public static void setMaxDur(long maxDur) {
		Task.maxDur = maxDur;
	}

	public void setEarlyStart(long earlyStart) {
		this.earlyStart = earlyStart;
	}

	public void setEarlyFinish(long earlyFinish) {
		this.earlyFinish = earlyFinish;
	}

	public void setCriticalPath(long criticalPath) {
		this.criticalPath = criticalPath;
	}

	/*
	 * The list of parent tasks.
	 */
    private List<Task> parentList;
    /*
     * The list of child tasks. 
     */
    private List<Task> childList;
    /*
     * The list of all files (input data and ouput data)
     */
    private List<FileItem> fileList;
    /*
     * The priority used for research. Not used in current version. 
     */
    private int priority;
    /*
     * The depth of this task. Depth of a task is defined as the furthest path 
     * from the root task to this task. It is set during the workflow parsing 
     * stage. 
     */
    private int depth;
    /*
     * The impact of a task. It is used in research. 
     */
    private double impact;

    /*
     * The type of a task. 
     */
    private String type;

    /**
     * The finish time of a task (Because cloudlet does not allow WorkflowSim to
     * update finish_time)
     */
    private double taskFinishTime;

    /**
     * Allocates a new Task object. The task length should be greater than or
     * equal to 1.
     *
     * @param taskId the unique ID of this Task
     * @param taskLength the length or size (in MI) of this task to be executed
     * in a PowerDatacenter
     * @pre taskId >= 0
     * @pre taskLength >= 0.0
     * @post $none
     */
    public Task(
            final int taskId,
            final long taskLength) {
        /**
         * We do not use cloudletFileSize and cloudletOutputSize here. We have
         * added a list to task and thus we don't need a cloudletFileSize or
         * cloudletOutputSize here The utilizationModelCpu, utilizationModelRam,
         * and utilizationModelBw are just set to be the default mode. You can
         * change it for your own purpose.
         */
        super(taskId, taskLength, 1, 0, 0, new UtilizationModelFull(), new UtilizationModelFull(), new UtilizationModelFull());

        this.childList = new ArrayList<>();
        this.parentList = new ArrayList<>();
        this.fileList = new ArrayList<>();
        this.impact = 0.0;
        this.taskFinishTime = -1.0;
    }

    /**
     * Sets the type of the task
     *
     * @param type the type
     */
    public void setType(String type) {
        this.type = type;
    }

    /**
     * Gets the type of the task
     *
     * @return the type of the task
     */
    public String getType() {
        return type;
    }

    /**
     * Sets the priority of the task
     *
     * @param priority the priority
     */
    public void setPriority(int priority) {
        this.priority = priority;
    }

    /**
     * Sets the depth of the task
     *
     * @param depth the depth
     */
    public void setDepth(int depth) {
        this.depth = depth;
    }

    /**
     * Gets the priority of the task
     *
     * @return the priority of the task
     * @pre $none
     * @post $none
     */
    public int getPriority() {
        return this.priority;
    }

    /**
     * Gets the depth of the task
     *
     * @return the depth of the task
     */
    public int getDepth() {
        return this.depth;
    }

    /**
     * Gets the child list of the task
     *
     * @return the list of the children
     */
    public List<Task> getChildList() {
        return this.childList;
    }

    /**
     * Sets the child list of the task
     *
     * @param list, child list of the task
     */
    public void setChildList(List<Task> list) {
        this.childList = list;
    }

    /**
     * Sets the parent list of the task
     *
     * @param list, parent list of the task
     */
    public void setParentList(List<Task> list) {
        this.parentList = list;
    }

    /**
     * Adds the list to existing child list
     *
     * @param list, the child list to be added
     */
    public void addChildList(List<Task> list) {
        this.childList.addAll(list);
    }

    /**
     * Adds the list to existing parent list
     *
     * @param list, the parent list to be added
     */
    public void addParentList(List<Task> list) {
        this.parentList.addAll(list);
    }

    /**
     * Gets the list of the parent tasks
     *
     * @return the list of the parents
     */
    public List<Task> getParentList() {
        return this.parentList;
    }

    /**
     * Adds a task to existing child list
     *
     * @param task, the child task to be added
     */
    public void addChild(Task task) {
        this.childList.add(task);
    }

    /**
     * Adds a task to existing parent list
     *
     * @param task, the parent task to be added
     */
    public void addParent(Task task) {
        this.parentList.add(task);
    }

    /**
     * Gets the list of the files
     *
     * @return the list of files
     * @pre $none
     * @post $none
     */
    public List<FileItem> getFileList() {
        return this.fileList;
    }

    /**
     * Adds a file to existing file list
     *
     * @param file, the file to be added
     */
    public void addFile(FileItem file) {
        this.fileList.add(file);
    }

    /**
     * Sets a file list
     *
     * @param list, the file list
     */
    public void setFileList(List<FileItem> list) {
        this.fileList = list;
    }

    /**
     * Sets the impact factor
     *
     * @param impact, the impact factor
     */
    public void setImpact(double impact) {
        this.impact = impact;
    }

    /**
     * Gets the impact of the task
     *
     * @return the impact of the task
     * @pre $none
     * @post $none
     */
    public double getImpact() {
        return this.impact;
    }

    /**
     * Sets the finish time of the task (different to the one used in Cloudlet)
     *
     * @param time finish time
     */
    public void setTaskFinishTime(double time) {
        this.taskFinishTime = time;
    }

    /**
     * Gets the finish time of a task (different to the one used in Cloudlet)
     *
     * @return
     */
    public double getTaskFinishTime() {
        return this.taskFinishTime;
    }

    /**
     * Gets the total cost of processing or executing this task The original
     * getProcessingCost does not take cpu cost into it also the data file in
     * Task is stored in fileList <tt>Processing Cost = input data transfer +
     * processing cost + output transfer cost</tt> .
     *
     * @return the total cost of processing Cloudlet
     * @pre $none
     * @post $result >= 0.0
     */
    @Override
    public double getProcessingCost() {
        // cloudlet cost: execution cost...

        double cost = getCostPerSec() * getActualCPUTime();

        // ...plus input data transfer cost...
        long fileSize = 0;
        for (FileItem file : getFileList()) {
            fileSize += file.getSize() / Consts.MILLION;
        }
        cost += costPerBw * fileSize;
        return cost;
    }
}
