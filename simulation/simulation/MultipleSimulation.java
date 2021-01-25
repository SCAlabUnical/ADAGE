package simulation;


import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Map.Entry;
import java.util.Random;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import readycli.Command;
import readycli.Option;
import utilpack.Tuple;
import utilpack.parsing.CSVReader;
import utilpack.parsing.CSVWriter;

public class MultipleSimulation {
	private static final Tuple LABELS = Tuple.ofStrings("File", "Scheduler", "Planner", "Bandwidth", "Nodes", "Simulation Time");
	
	//key: (simulation name, workflow name, nodes, bandwidth); value: (medium execution time)
	private static Map<Tuple, Tuple> results = new TreeMap<>();
	
	private static File resultsFile = new File("./Montage-results.csv");
	private static String logDir = "./log";

	private static Integer[] numbersOfNodes = {512};
	private static Integer[] bandwidths = {1000};
	private static String[][] strategies = {
			{"WORKGIVING", "CRITICAL_PATH"}
	};
	private static int simNumber = 1; // numero di simulazioni
	private static File daxDir = new File("./workflows/Montage/Montage_500.xml");
	private static int maxMemory = 14000;
	private static long seed = 0;
	private static long wgMode = 2;
	
	public static void main(String[] args) throws IOException{
		
		Command.defaultDocs("", "Executes multiple simulations with the given parameters")
		.addOption(Option.create("out", "specify the output CSV file where the collected results of simulations are stored.")
				.addParameter("value", "path to file", "./Montage-results.csv")
				.build())
		.addOption(Option.create("dax", "directory that contains *.dax files")
				.addParameter("value", "path to directory", "./workflows/Montage/Montage_500.xml")
				.build())
		.addOption(Option.create("wgmode", "specify the execution mode for the work-giving scheduler")
				.addParameter("value", "-1=DM only; 0=no replication; 1,2,3,.. = 1,2,3,.. task replicas", "2")
				.build())
		.addOption(Option.create("nodes", "specify the number of virtual machines")
				.addAlias("n")
				.addParameter("values", "integer values separated by semicolon (;)", "1;2;4;8;16;32;64;128;256;512;1024")
				.build())
		.addOption(Option.create("strategies", "specify the scheduling and the planning strategies to use")
				.addAlias("s")
				.addParameter("values", "all the strategies to simulate, separated by ';'. Each strategy is given in the format 'scheduler@planner'", "MATRIX@RANDOM;ALBATROSS@RANDOM;WORKGIVING@CRITICAL_PATH")
				.build())
		.addOption(Option.create("seed", "specify the seed used for the random generator")
				.addParameter("value", "long value, set to 'null' to use a random seed", "null")
				.build())
		.addOption(Option.create("log", "output directory for log file. The name of the file is given by the parameters of the simulation")
				.addParameter("value", "path to the log directory", "./log")
				.build())
		.addOption(Option.create("bandwidths", "bandwith of each virtual machine")
				.addAlias("b")
				.addParameter("values", "integer value in MB/s", "1000")
				.build())
		.addOption(Option.create("maxmem", "maximum total memory used for the simulation batch")
				.addAlias("m")
				.addParameter("value", "integer value in MB", "14000")
				.build())
		.addOption(Option.create("num", "the number of times a simulation is repeated. All repetition outputs are aggregated in average values.")
				.addParameter("value", "the number of repetition of a simulation", "3")
				.build())
		.build(ctx->{
			resultsFile = new File(ctx.getOption("out").get("value"));
			daxDir = new File(ctx.getOption("dax").get("value"));
			logDir = ctx.getOption("log").get("value");
			maxMemory = Integer.valueOf(ctx.getOption("maxmem").get("value"));
			simNumber = Integer.valueOf(ctx.getOption("num").get("value"));
			numbersOfNodes = Stream.of(ctx.getOption("nodes").get("values").split(";"))
					.map(v->Integer.parseInt(v))
					.collect(Collectors.toList())
					.toArray(new Integer[0]);
			bandwidths = Stream.of(ctx.getOption("bandwidths").get("values").split(";"))
					.map(v->Integer.parseInt(v))
					.collect(Collectors.toList())
					.toArray(new Integer[0]);
			strategies = Stream.of(ctx.getOption("strategies").get("values").split(";"))
					.map(s->s.split("@"))
					.collect(Collectors.toList())
					.toArray(new String[0][]);
			String seedString = ctx.getOption("seed").get("value");
			if(seedString.equalsIgnoreCase("null"))
				seed = System.currentTimeMillis();
			else
				seed = Long.parseLong(seedString);
			wgMode = Integer.parseInt(ctx.getOption("wgmode").get("value"));
		})
		.execute(args);
		
		Random random = new Random();
		System.out.println("simNumber="+simNumber);
		System.out.println("maximum RAM memory: " + maxMemory + " MB");
		
		int maxMemoryPerVm = maxMemory/Runtime.getRuntime().availableProcessors();
		System.out.println("maximum RAM memory per VM: " + maxMemoryPerVm + " MB");
		
		File[] daxFiles;
		if(daxDir.isDirectory())
			daxFiles = daxDir.listFiles();
		else
			daxFiles=new File[] {daxDir};
		
		Lock mutex = new ReentrantLock();
		if(resultsFile.exists())
			try(CSVReader csv = new CSVReader(resultsFile, ';')){
				if(csv.hasNextTuple())
					csv.read(); // LABELS row
				while(csv.hasNextTuple()) {
					Tuple tuple = csv.read();
					Tuple key = tuple.subTuple(0, 5);
					Tuple value = tuple.subTuple(5, tuple.length());
					results.put(key, value);
				}
			}
		
		try(CSVWriter csv = new CSVWriter(resultsFile, ';', true)){
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
						
						index[0] = (index[0]+1) % daxFiles.length;
						if(index[0]==0) {
							index[1] = (index[1]+1) % strategies.length;
							if(index[1]==0) {
								index[2] = (index[2]+1) % bandwidths.length;
								if(index[2]==0) {
									index[3] = (index[3]+1) % numbersOfNodes.length;
									if(index[3] == 0)
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
								bandwidths[index[2]],
								numbersOfNodes[index[3]]))
			.filter(simKey->!results.containsKey(simKey))
			.flatMap(simKey->Stream.iterate(1, i->i<=simNumber, i->i+1).parallel().map(i->simKey.add(i)))
			.peek(simKey-> System.out.println("keeping config" + simKey))
			.collect(Collectors.toList())
			.parallelStream()
			.map(config->{
				String parentPath;
				if(daxDir.isDirectory())
					parentPath=daxDir.getAbsolutePath();
				else
					parentPath=daxDir.getParentFile().getAbsolutePath();
				Tuple simKey = config.subTuple(0, 5);
				File daxFile = Paths.get(parentPath, (String)simKey.get(0)).toFile();
				String scheduler = simKey.get(1);
				String planner = simKey.get(2);
				int bw = simKey.get(3);
				int nodes = simKey.get(4);
				int simIndex = config.get(5);
				
				Runtime runtime = Runtime.getRuntime();
				String commandFormat = "java -Xms%dm -Xmx%dm -jar simulation.jar %s --log %s --sched %s --plan %s --bw %d --nodes %d --wgmode %s --seed %d";

				double simTime = 0d;
				int backupTasks = 0;
				int nonBackupTasks = 0;
				boolean success = false;
				while(!success) //retry the simulation until it ends successfully
					try {
						System.out.println("starting simulation "+config);
						String command = String.format(commandFormat, 
								maxMemoryPerVm, maxMemoryPerVm, daxFile.toString(), logDir, scheduler, 
								planner, bw, nodes, wgMode, 
								(seed==0?random.nextLong():seed));
						Process process = runtime.exec(command);
						runtime.addShutdownHook(new Thread(process::destroyForcibly));
						int res = process.waitFor();
						if(res == 0) {
							String[] result = new String(process.getInputStream().readAllBytes()).split(";");
							System.out.printf("%s: execTime=%s; backupTasks=%s; nonBackupTasks=%s\n", config, result[0], result[1], result[2]);
							simTime = Double.parseDouble(result[0].replace(",", "."));
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
					
				
				return config.add(simTime);
			})
			.forEach(simTuple->{
				Tuple simKey = simTuple.subTuple(0, 5);
				int simIndex = simTuple.get(5);
				Tuple simResult = simTuple.subTuple(6, simTuple.length());
				
				// results
				Double simTime = simResult.get(0);
				
				mutex.lock();
				try {
					double currentSimTime = 0;
					int numSim = 0;
					if(results.containsKey(simKey)) {
						Tuple value = results.get(simKey);
						currentSimTime = Double.parseDouble(value.get(0));
						numSim = Integer.valueOf(value.get(1));
					}
					currentSimTime += simTime/simNumber;
					numSim++;
					results.put(simKey, Tuple.ofStrings(currentSimTime, numSim));
					
					if(numSim >= simNumber) {
						Tuple csvTuple = simKey.add(String.format("%1.3f", currentSimTime));
						csv.write(csvTuple); // write into csv for backup purposes, results will be unordered
					}
				} catch (IOException e) {
					e.printStackTrace();
				} finally {
					mutex.unlock();
				}
			});
			
		}
		
		// re-write the csv file to sort the tuples
		try(CSVWriter csv = new CSVWriter(resultsFile, ';', false)){
			csv.write(LABELS);
			for(Entry<Tuple, Tuple> e : results.entrySet()) {
				Tuple value = e.getValue();
				double currentSimTime = Double.parseDouble(value.get(0).toString().replace(",", "."));
				csv.write(e.getKey().add(String.format("%1.3f", currentSimTime)));
			}
		}
	}
}
