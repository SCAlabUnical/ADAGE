package simulation;


import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import readycli.CLI;
import readycli.Command;
import readycli.Option;
import utilpack.Tuple;
import utilpack.parsing.CSVReader;
import utilpack.parsing.CSVWriter;
import utilpack.parsing.arguments.ArgumentParser;
import utilpack.parsing.arguments.ArgumentParser.ArgumentProcessor;
import utilpack.parsing.arguments.ExpectedArgumentException;
import utilpack.parsing.arguments.ExpectedOptionParameterException;
import utilpack.parsing.arguments.UnexpectedArgumentException;

public class SimulationBackupTasks {
	
	//key: (simulation name, workflow name, nodes, bandwidth); value: (medium execution time)
	private static Map<Tuple, Tuple> results = new TreeMap<>();
	
	private static File backupFile = new File("./SimulationBackupTasks.backup");
	private static String logDir = "./log";

	private static Integer[] numbersOfNodes = {32,64};
	private static Integer[] bandwidths = {1000};
	private static String[][] strategies = {
			{"WORKGIVING", "CRITICAL_PATH"}
	};
	private static File daxDir = new File("./workflows/Montage");
	private static File backupTasksCsvDir = new File("./sim-dataset/Montage/backupTasks");
	private static int maxMemory = 14000;
	
	private Lock mutex = new ReentrantLock();
	
	public static void main(String[] args) throws IOException, ExpectedOptionParameterException, ExpectedArgumentException, UnexpectedArgumentException {
		
		Command.defaultDocs("", "SimulationBackupTasks - Executes simulations on the WG+CP strategy, counting tasks that executes on backup nodes")
				.addOption(Option.create("dax", "Directory that contains *.dax files or a single *.dax file")
						.addAlias("d")
						.addParameter("value", "Directory or file path", "./workflows/CyberShake")
						.build())
				.addOption(Option.create("nodes", "The nodes numbers to simulate on")
						.addAlias("n")
						.addParameter("value", "A list of numbers separated by minus ('-') characters" , "32-64")
						.build())
				.addOption(Option.create("maxmem", "Sets the maximum RAM memory used by all simulations")
						.addAlias("m")
						.addParameter("value", "the maximum RAM memory in Megabytes (MB)" , "6144")
						.build())
				.addOption(Option.create("bws", "All the bandwidth values to simulate")
						.addAlias("b")
						.addParameter("value", "bandwidth values, separated by minus ('-') characters" , "1000")
						.build())
				.addOption(Option.create("log", "The directory where the LOGs are stored")
						.addAlias("l")
						.addParameter("value", "Directory path" , "./log")
						.build())
				.addOption(Option.create("out", "output directory for the CSV files that store the numbers of tasks "
							+ "that are executed on backup nodes or not. The name of the file is given by the "
							+ "parameters of the simulation.")
						.addAlias("o")
						.addParameter("value", "Directory path" , "./sim-dataset/Montage/backupTasks")
						.build())
				.addOption(Option.create("backup", "Backup file used to restore previous non-terminated simulations")
						.addAlias("bk")
						.addParameter("value", "File path" , "./SimulationBackupTasks.backup")
						.build())
				.build(ctx->{
					backupFile = new File(ctx.getOption("backup").get("value"));
					backupTasksCsvDir = new File(ctx.getOption("out").get("value"));
					daxDir = new File(ctx.getOption("dax").get("value"));
					logDir = ctx.getOption("log").get("value");
					maxMemory = Integer.parseInt(ctx.getOption("maxmem").get("value"));
					numbersOfNodes = Stream.of(ctx.getOption("nodes").get("value").split("-"))
							.map(v->Integer.parseInt(v))
							.collect(Collectors.toList())
							.toArray(new Integer[0]);
					bandwidths = Stream.of(ctx.getOption("bws").get("value").split("-"))
						.map(v->Integer.parseInt(v))
						.collect(Collectors.toList())
						.toArray(new Integer[0]);
					
						try {
							simulate();
						} catch (IOException e) {
							e.printStackTrace();
						}
					ctx.out.println("Simulation finished.");
				})
				.execute(args);
	}
	
	private static void simulate() throws FileNotFoundException, IOException {
		System.out.println("maximum RAM memory: " + maxMemory + " MB");
		
		int maxMemoryPerVm = maxMemory/Runtime.getRuntime().availableProcessors();
		System.out.println("maximum RAM memory per VM: " + maxMemoryPerVm + " MB");
		
		File[] daxFiles;
		if(daxDir.isDirectory())
			daxFiles = daxDir.listFiles();
		else {
			daxFiles = new File[] {daxDir};
			daxDir = daxDir.getParentFile();
		}
		
		Lock mutex = new ReentrantLock();
		if(backupFile.exists())
			try(CSVReader backupCsv = new CSVReader(backupFile, ';')){
				// (daxFile, scheduler, planner, bandwidth, nodes,    |   simTime, backupTasks, nonBackupTasks)
				while(backupCsv.hasNextTuple()) {
					Tuple tuple = backupCsv.read();
					Tuple simKey = tuple.subTuple(0, 5);
					Tuple simResults = tuple.subTuple(5, tuple.length());
					results.put(simKey, simResults);
				}
			}
		try(CSVWriter backupCsv = new CSVWriter(backupFile, ';', true)){
			// do all simulations
			Stream.iterate(new int[] {0,0,0,0},
					index->{
						if(index[0] == -1)
							return false;
						return true;
					},
					previousIndex->{
						int[] index = new int[previousIndex.length];
						System.arraycopy(previousIndex, 0, index, 0, previousIndex.length);
						
						index[3] = (index[3]+1) % numbersOfNodes.length;
						if(index[3]==0) {
							index[2] = (index[2]+1) % bandwidths.length;
							if(index[2]==0) {
								index[1] = (index[1]+1) % strategies.length;
								if(index[1]==0) {
									index[0] = (index[0]+1) % daxFiles.length;
									if(index[0] == 0)
										index[0] = -1;
								}
							}
						}
						return index;
					}
				)
			.map(index->Tuple.ofObjects(
								daxFiles[index[0]].getName(),
								strategies[index[1]][0], // scheduler
								strategies[index[1]][1], // planner
								""+bandwidths[index[2]],
								""+numbersOfNodes[index[3]]))
			.filter(simKey->simKey.<String>get(0).matches(".+_[0-9]+(\\..*)?"))
			.filter(simKey->!results.containsKey(simKey))
			.peek(simKey-> System.out.println("keeping config" + simKey))
			.collect(Collectors.toList())
			.parallelStream()
			.map(simKey->{
				File daxFile = Paths.get(daxDir.getAbsolutePath(), (String)simKey.get(0)).toFile();
				String scheduler = simKey.get(1);
				String planner = simKey.get(2);
				int bw = Integer.parseInt(simKey.get(3));
				int nodes = Integer.parseInt(simKey.get(4));
				
				Runtime runtime = Runtime.getRuntime();
				String commandFormat = "java -Xms%dm -Xmx%dm -jar simulation.jar %s --log %s --sched %s --plan %s --bw %d --nodes %d";
	
				double simTime = 0d;
				int backupTasks = 0;
				int nonBackupTasks = 0;
				boolean success = false;
				while(!success) //retry the simulation until it ends successfully
					try {
						System.out.println("starting simulation "+simKey);
						String command = String.format(commandFormat, maxMemoryPerVm, maxMemoryPerVm, daxFile.toString(), logDir, scheduler, planner, bw, nodes);
						Process process = runtime.exec(command);
						runtime.addShutdownHook(new Thread(process::destroyForcibly));
						int res = process.waitFor();
						if(res == 0) {
							String[] result = new String(process.getInputStream().readAllBytes()).split(";");
							System.out.printf("%s: execTime=%s; backupTasks=%s; nonBackupTasks=%s\n", simKey, result[0], result[1], result[2]);
							simTime = Double.parseDouble(result[0]);
							backupTasks = Integer.parseInt(result[1]);
							nonBackupTasks = Integer.parseInt(result[2]);
							success=true;
						} else {
							mutex.lock();
							try {
								System.out.println("Smulation eneded with error status " + res);
								System.out.println("=====Start of simulation output log=====\n");
								System.out.println(new String(process.getInputStream().readAllBytes()));
								System.out.println("======End of simulation output log======\n");
								System.out.println("=====Start of simulation error log=====\n");
								System.out.println(new String(process.getErrorStream().readAllBytes()));
								System.out.println("======End of simulation error log======\n");
							}finally {
								mutex.unlock();
							}
						}
					} catch (Exception e) {
						e.printStackTrace();
					}
					
				
				return simKey.add(String.format("%1.3f", simTime), backupTasks, nonBackupTasks);
			})
			.forEach(simTuple->{
				Tuple simKey = simTuple.subTuple(0, 5);
				Tuple simResult = simTuple.subTuple(5, simTuple.length());
				
				synchronized (results) {
					results.put(simKey, simResult);
					try {
						backupCsv.write(simTuple);
					} catch (IOException e1) {
						e1.printStackTrace();
					}
				}
	
				File daxFile = Paths.get(daxDir.getAbsolutePath(), simKey.<String>get(0)).toFile();
				int bw = Integer.parseInt(simKey.get(3));
				int nodes = Integer.parseInt(simKey.get(4));
				
				String[] workflow = daxFile.getName().split("\\.")[0].split("_");
				String appName = workflow[0];
				String taskNum = workflow[1];
				
				// results
				String simTime = simResult.get(0);
				int backupTasks = simResult.get(1);
				int nonBackupTasks = simResult.get(2);
				
				mutex.lock();
				try {
					//filename: app-tasks-bw
		        	//columns: nodes-simTime-backupTasks-nonBackupTasks
		        	File backupTasksPerNodeFile = Paths.get(
		        			backupTasksCsvDir.getAbsolutePath(),
		        			"perNode",
		        			String.format("%s_bw%s_tasks%s.csv", appName, bw, taskNum)
		        		).toFile();
		        	
		        	writeTupleInSortedCsv(
		        			Tuple.ofStrings("Number of nodes", "Simulation time", "Tasks completed as backup", "Tasks completed normally"), 
		        			Tuple.ofStrings(nodes, simTime, backupTasks, nonBackupTasks), 
		        			backupTasksPerNodeFile,
		        			(t1, t2)->{
		        	    		Integer f1 = Integer.parseInt(t1.getFirst());
		        	    		Integer f2 = Integer.parseInt(t2.getFirst());
		        	    		return f1.compareTo(f2);
		        	    	});
		        	
		        	//filename: app-nodes-bw
		        	//columns: tasks-backupTasks-nonBackupTasks
		        	File backupTasksPerTaskFile = Paths.get(
		        			backupTasksCsvDir.getAbsolutePath(), 
		        			"perTask",
		        			String.format("%s_bw%s_nodes%s.csv", appName, bw, nodes)
		        		).toFile();
		        	
		        	writeTupleInSortedCsv(
		        			Tuple.ofStrings("Number of tasks", "Simulation time", "Tasks completed as backup", "Tasks completed normally"), 
		        			Tuple.ofStrings(taskNum, simTime, backupTasks, nonBackupTasks), 
		        			backupTasksPerTaskFile,
		        			(t1, t2)->{
		        	    		Integer f1 = Integer.parseInt(t1.getFirst());
		        	    		Integer f2 = Integer.parseInt(t2.getFirst());
		        	    		return f1.compareTo(f2);
		        	    	});
				} finally {
					mutex.unlock();
				}
			});
		
		}
	}
	
	private static void writeTupleInSortedCsv(Tuple columns, Tuple tuple, File csvFile, Comparator<Tuple> comparator) {
		//filename: app-tasks-bw
    	//columns: nodes-simTime-backupTasks-nonBackupTasks
    	
		csvFile.getParentFile().mkdirs();
		
    	Set<Tuple> tuples = new TreeSet<>(comparator);
    	
		tuples.add(tuple);
    	
    	if(csvFile.exists())
    		try(CSVReader csv = new CSVReader(csvFile, ';')){
    			csv.read(); // drops the columns row
    			while(csv.hasNextTuple())
    				tuples.add(csv.read());
    		} catch (FileNotFoundException e) {
    			e.printStackTrace();
    		} catch (IOException e) {
    			e.printStackTrace();
    		}
		
		try(CSVWriter csv = new CSVWriter(csvFile, ';', false)){
			csv.write(columns);
			for(Tuple sorted : tuples)
				csv.write(sorted);
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
