package simulation;
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


import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.logging.ConsoleHandler;
import java.util.Map.Entry;

import org.cloudbus.cloudsim.CloudletSchedulerSpaceShared;
import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.HarddriveStorage;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.ParameterException;
import org.cloudbus.cloudsim.Pe;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.VmAllocationPolicySimple;
import org.cloudbus.cloudsim.VmSchedulerTimeShared;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.lists.HostList;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.workflowsim.ClusterRamDiskStorage;
import org.workflowsim.ClusterStorage;
import org.workflowsim.CondorVM;
import org.workflowsim.Job;
import org.workflowsim.Task;
import org.workflowsim.WorkflowDatacenter;
import org.workflowsim.WorkflowEngine;
import org.workflowsim.WorkflowPlanner;
import org.workflowsim.scheduling.WorkGivingSchedulingAlgorithm;
import org.workflowsim.utils.ClusteringParameters;
import org.workflowsim.utils.OverheadParameters;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.Parameters.ClassType;
import org.workflowsim.utils.Parameters.PlanningAlgorithm;
import org.workflowsim.utils.Parameters.SchedulingAlgorithm;
import org.workflowsim.utils.ReplicaCatalog;

import readycli.Command;
import readycli.Option;
import utilpack.TableView;
import utilpack.TableView.Row;
import utilpack.Tuple;
import utilpack.logging.Logger;
import utilpack.logging.Loggers;
import utilpack.logging.handler.text.ConsoleLogHandler;
import utilpack.logging.handler.text.SimpleLogFormatter;
import utilpack.logging.handler.text.TextLogHandler;
import utilpack.parsing.CSVReader;
import utilpack.parsing.CSVWriter;
import utilpack.parsing.arguments.ArgumentParser;
import utilpack.parsing.arguments.ExpectedArgumentException;
import utilpack.parsing.arguments.ExpectedOptionParameterException;
import utilpack.parsing.arguments.UnexpectedArgumentException;

/**
 * This WorkflowSimExample creates a workflow planner, a workflow engine, and
 * one schedulers, one data centers and 20 vms. You should change daxPath at
 * least. You may change other parameters as well.
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Apr 9, 2013
 */
public class Simulation {

	private static Logger logger = Loggers.getLogger("WorkflowSim");;
	private static int vmNum = 100;//number of vms;
	private static SchedulingAlgorithm scheduling = SchedulingAlgorithm.WORKGIVING;
	private static PlanningAlgorithm planning = PlanningAlgorithm.RANDOM;
	private static String daxPath = "./config/dax/Montage_1000.xml";
	private static String logPath = "./log";
	private static long bandwidth = 1000;
	private static double finishTime;
	
	private static int nodeMips = 2000;
	private static int nodeRam = 8192;
	private static int nodeCores = 4;

	protected static List<CondorVM> createVM(int userId, int vms, int vmIdBase) {

        //Creates a container to store VMs. This list is passed to the broker later
        LinkedList<CondorVM> list = new LinkedList<>();

        //VM Parameters
        long size = 10000; //image size (MB)
        int ram = nodeRam; //vm memory (MB)
        int mips = nodeMips;
        long bw = bandwidth;
        int pesNumber = nodeCores; //number of cpus
        String vmm = "Xen"; //VMM name

		LinkedList<Storage> storageList = new LinkedList<>();
		double interBandwidth = 1000;
		double intraBandwidth = interBandwidth * 2;

        //create VMs
        CondorVM[] vm = new CondorVM[vms];
        for (int i = 0; i < vms; i++) {
			vm[i] = new CondorVM(vmIdBase + i, userId, mips, pesNumber, ram, bw, size, vmm,
					new CloudletSchedulerSpaceShared());
            list.add(vm[i]);
			String name = "vm_" + (vmIdBase+i);
			ClusterStorage s1 = null;
			ClusterRamDiskStorage s2 = null;
			try {
				s1 = new ClusterStorage(name, 1e6);
				s2 = new ClusterRamDiskStorage(name, 6200);
				s1.setBandwidth("local", intraBandwidth);
				s2.setBandwidth("local", intraBandwidth);
				s1.setBandwidth("source", interBandwidth);
				s2.setBandwidth("source", interBandwidth);
			} catch (ParameterException e) {
				e.printStackTrace();
			}

			storageList.add(s1);
			storageList.add(s2);
			vm[i].setStorage(storageList);
        }
        return list;
    }
	private static WorkflowDatacenter createDatacenter(String name) {
	
	    // Here are the steps needed to create a PowerDatacenter:
	    // 1. We need to create a list to store one or more
	    //    Machines
	    List<Host> hostList = new ArrayList<>();
	
	    // 2. A Machine contains one or more PEs or CPUs/Cores. Therefore, should
	    //    create a list to store these PEs before creating
	    //    a Machine.
        int hostId = 0;
	    for (int i = 1; i <= vmNum; i++) {
	        List<Pe> peList1 = new ArrayList<>();
	        int mips = nodeMips;
	        // 3. Create PEs and add these into the list.
	        //for a quad-core machine, a list of 4 PEs is required:
	        for(int core=0; core<nodeCores; core++)
		        peList1.add(new Pe(core, new PeProvisionerSimple(mips)));
	        int ram = nodeRam; //host memory (MB)
	        long storage = 1000000; //host storage
	        long bw = bandwidth;
	        hostList.add(
	                new Host(
	                        hostId,
	                        new RamProvisionerSimple(ram),
	                        new BwProvisionerSimple(bw),
	                        storage,
	                        peList1,
	                        new VmSchedulerTimeShared(peList1))); // This is our first machine
	        hostId++;
	    }
	
	    // 4. Create a DatacenterCharacteristics object that stores the
	    //    properties of a data center: architecture, OS, list of
	    //    Machines, allocation policy: time- or space-shared, time zone
	    //    and its price (G$/Pe time unit).
	    String arch = "x86";      // system architecture
	    String os = "Linux";          // operating system
	    String vmm = "Xen";
	    double time_zone = 10.0;         // time zone this resource located
	    double cost = 3.0;              // the cost of using processing in this resource
	    double costPerMem = 0.05;		// the cost of using memory in this resource
	    double costPerStorage = 0.1;	// the cost of using storage in this resource
	    double costPerBw = 0.1;			// the cost of using bw in this resource
	    LinkedList<Storage> storageList = new LinkedList<>();	//we are not adding SAN devices by now
	    WorkflowDatacenter datacenter = null;
	    
	    logger.info("numberOfPes=%d", HostList.getNumberOfPes(hostList));
	
	    DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
	            arch, os, vmm, hostList, time_zone, cost, costPerMem, costPerStorage, costPerBw);
	
	    // 5. Finally, we need to create a storage object.
	    
	    /**
	     * The bandwidth within a data center in MB/s.
	     */
	    int maxTransferRate = 15;// the number comes from the futuregrid site, you can specify your bw
	
	    try {
	        // Here we set the bandwidth to be 15MB/s
	        HarddriveStorage s1 = new HarddriveStorage(name, 1e12);
	        s1.setMaxTransferRate(maxTransferRate);
	        storageList.add(s1);
	        datacenter = new WorkflowDatacenter(name, characteristics, new VmAllocationPolicySimple(hostList), storageList, 0);
	    } catch (Exception e) {
	        e.printStackTrace();
	    }
	    return datacenter;
	}

	/**
	 * Prints the job objects
	 *
	 * @param list list of jobs
	 * @throws FileNotFoundException 
	 */
	private static void printJobList(List<Job> list) throws FileNotFoundException {
	    TableView table = new TableView();
	    table.row("Job ID", "Task IDs", "STATUS", "Data center ID", "VM ID", "Time", "Start Time", "Finish Time", "Depth");
	    DecimalFormat dft = new DecimalFormat("###.##");
	    for (Job job : list) {
	    	Row row = table.row();
	    	row.add(job.getCloudletId());
	        if (job.getClassType() == ClassType.STAGE_IN.value)
	        	row.add("Stage-in");
	        else {
	        	List<Integer> taskIds = new LinkedList<Integer>();
		        for (Task task : job.getTaskList())
		        	taskIds.add(task.getCloudletId());
	        	row.add(taskIds);
	        }
        	row.add(job.getCloudletStatusString());
            row.add(job.getResourceId(), job.getVmId(), dft.format(job.getActualCPUTime()));
            row.add(dft.format(job.getExecStartTime()),dft.format(job.getFinishTime()),job.getDepth());
	    }

	    logger.info(table.toString());
	}

	////////////////////////// STATIC METHODS ///////////////////////
    /**
     * Creates main() to run this example This example has only one datacenter
     * and one storage
     * @throws UnexpectedArgumentException 
     * @throws ExpectedArgumentException 
     * @throws ExpectedOptionParameterException 
     */
	public static void main(String[] args) throws ExpectedOptionParameterException, ExpectedArgumentException, UnexpectedArgumentException {
		
		Command.defaultDocs("", "Simulation - Performs a single workflow simulation with the given parameters")
		.addRequiredArgument("dax", "path to the DAX file that contains the workflow to simulate on")
		.addOption(Option.create("nodes", "specify the number of virtual machines")
				.addParameter("value", "integer value", ""+vmNum)
				.build())
		.addOption(Option.create("wgmode", "specify the execution mode for the work-giving scheduler")
				.addParameter("value", "-1=DM only; 0=no replication; 1,2,3,.. = 1,2,3,.. task replicas", ""+2)
				.build())
		.addOption(Option.create("seed", "specify the seed used for the random generator")
				.addParameter("value", "long value, set to 'null' to use a random seed", "null")
				.build())
		.addOption(Option.create("log", "output directory for log file. The name of the file is given by the parameters of the simulation")
				.addParameter("value", "path to the log directory", logPath)
				.build())
		.addOption(Option.create("bw", "bandwith of each virtual machine")
				.addParameter("value", "integer value in MB/s", ""+bandwidth)
				.build())
		.addOption(Option.create("sched", "the scheduling strategy to simulate. "
				+ "Possible values are: " + Arrays.toString(SchedulingAlgorithm.values()))
				.addParameter("value", "name of the scheduling strategy", scheduling.toString())
				.build())
		.addOption(Option.create("plan", "the planning algorithm to simulate. "
				+ "Possible values are: " + Arrays.toString(PlanningAlgorithm.values()))
				.addParameter("value", "name of the scheduling strategy", planning.toString())
				.build())
		.build(ctx->{
			daxPath = ctx.getArgument("dax");
			vmNum = Integer.valueOf(ctx.getOption("nodes").get("value"));
			int wgMode = Integer.valueOf(ctx.getOption("wgmode").get("value"));
			switch(wgMode) {
			case -1:
				WorkGivingSchedulingAlgorithm.workgivingActive = false;
				break;
			case 0:
				WorkGivingSchedulingAlgorithm.workgivingActive = true;
				WorkGivingSchedulingAlgorithm.replicationActive = false;
				break;
			default:
				WorkGivingSchedulingAlgorithm.workgivingActive = true;
				WorkGivingSchedulingAlgorithm.replicationActive = true;
				Task.maxNumBackup = Math.max(1, Math.abs(wgMode));
				break;
			}
			String seed = ctx.getOption("seed").get("value");
			if(seed.equals("null"))
				System.setProperty("simulation.seed", ""+System.currentTimeMillis());
			else 
				System.setProperty("simulation.seed", seed);
			logPath=ctx.getOption("log").get("value");
			bandwidth=Integer.valueOf(ctx.getOption("bw").get("value"));
			scheduling=SchedulingAlgorithm.valueOf(ctx.getOption("sched").get("value"));
			planning=PlanningAlgorithm.valueOf(ctx.getOption("plan").get("value"));
		})
		.execute(args);
    	
    	if(scheduling == SchedulingAlgorithm.WORKGIVING) {
    		logger.info("using Work Giving strategy");
    		logger.info("work giving active: %s", WorkGivingSchedulingAlgorithm.workgivingActive);
    		logger.info("replication active: %s", WorkGivingSchedulingAlgorithm.replicationActive);
    	}
    	System.setProperty("simulation.seed", "0");
    	//System.out.println("Bandwidth="+bandwidth);
    	new File(logPath).mkdirs();
    	File daxFile = new File(daxPath);
    	
        try {
        	
        	String logName = String.format("%s.%s.nodes_%d.bw_%d.file_%s", scheduling, planning, vmNum, bandwidth, daxFile.getName());
        	File logFile = Paths.get(logPath, logName + ".log").toFile();
        	
			PrintStream logStream = new PrintStream(logFile);
			logger.attachLogHandler(new TextLogHandler(logStream, new SimpleLogFormatter()));
			//logger.attachLogHandler(ConsoleLogHandler.getInstance());
			
			
            // First step: Initialize the WorkflowSim package. 
            /**
             * However, the exact number of vms may not necessarily be vmNum If
             * the data center or the host doesn't have sufficient resources the
             * exact vmNum would be smaller than that. Take care.
             */
            
            /**
             * Should change this based on real physical path
             */
            logger.info("starting simulation...");
            if (!daxFile.exists()) {
                Log.printLine("Warning: Please replace daxPath with the physical path in your working environment!");
                return;
            }

            /**
             * Since we are using MINMIN scheduling algorithm, the planning
             * algorithm should be INVALID such that the planner would not
             * override the result of the scheduler
             */
            Parameters.SchedulingAlgorithm sch_method = scheduling;
            Parameters.PlanningAlgorithm pln_method = planning;
            ReplicaCatalog.FileSystem file_system = ReplicaCatalog.FileSystem.SHARED;

            /**
             * No overheads
             */
            OverheadParameters op = new OverheadParameters(0, null, null, null, null, 0);

            /**
             * No Clustering
             */
            ClusteringParameters.ClusteringMethod method = ClusteringParameters.ClusteringMethod.NONE;
            ClusteringParameters cp = new ClusteringParameters(0, 0, method, null);

            /**
             * Initialize static parameters
             */
            Parameters.init(vmNum, daxPath, null,
                    null, op, cp, sch_method, pln_method,
                    null, 0);
            ReplicaCatalog.init(file_system);

            // before creating any entities.
            int num_user = 1;   // number of grid users
            Calendar calendar = Calendar.getInstance();
            boolean trace_flag = false;  // mean trace events

            // Initialize the CloudSim library
            CloudSim.init(num_user, calendar, trace_flag);

            WorkflowDatacenter datacenter0 = createDatacenter("Datacenter_0");

            /**
             * Create a WorkflowPlanner with one schedulers.
             */
            WorkflowPlanner wfPlanner = new WorkflowPlanner("planner_0", 1);
            /**
             * Create a WorkflowEngine.
             */
            WorkflowEngine wfEngine = wfPlanner.getWorkflowEngine();
            /**
             * Create a list of VMs.The userId of a vm is basically the id of
             * the scheduler that controls this vm.
             */
            List<CondorVM> vmlist0 = createVM(wfEngine.getSchedulerId(0), Parameters.getVmNum(), 0);

            /**
             * Submits this list of vms to this WorkflowEngine.
             */
            wfEngine.submitVmList(vmlist0, 0);

            /**
             * Binds the data centers with the scheduler.
             */
            wfEngine.bindSchedulerDatacenter(datacenter0.getId(), 0);
            CloudSim.startSimulation();
            List<Job> outputList = wfEngine.getJobsReceivedList();
            CloudSim.stopSimulation();
            printJobList(outputList);
            
            /*tasks completion times*/ {
                TreeMap<Double, Integer> reduceMap = new TreeMap<>();
	        	int numCompleted=0;

	        	for(Job job : outputList) 
					reduceMap.put(job.getFinishTime(), ++numCompleted);
	        	
	        	try(CSVWriter csv = new CSVWriter(Paths.get(logPath, "tasks-" + logName + ".csv").toFile(), ';', false)){
	    			csv.write(Tuple.ofObjects("time","tasks"));
	    			for(Entry<Double, Integer> e : reduceMap.entrySet()) {
	    				csv.write(Tuple.ofObjects(
	    						String.format("%1.3f", e.getKey()), 
	    						e.getValue().toString()
	    					));
	    			}
	    		}
            }
            
            for(Job job : outputList)
    	        finishTime = Math.max(finishTime, job.getFinishTime());
            
            // dax-file, scheduling, planning, bandwidth, nodes, finish-time
            String csvLine = String.format("%s; %s; %s; %d; %d; %1.3f\n", 
            		daxFile.getName(), scheduling, planning, 
            		vmNum, bandwidth, finishTime);
            
            logger.info(csvLine);
			
			logStream.close();
			logger.info("simulation ended");
			
			System.out.printf("%s;%s;%s", finishTime, WorkGivingSchedulingAlgorithm.backupCounter, WorkGivingSchedulingAlgorithm.nonBackupCounter);
			System.exit(0);
        } catch (Exception e) {
            Log.printLine("The simulation has been terminated due to an unexpected error");
            e.printStackTrace();
            System.exit(-1);
        }
    }
}
