package simulation;


import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import readycli.Command;
import readycli.Option;
import utilpack.Tuple;
import utilpack.parsing.CSVReader;
import utilpack.parsing.CSVWriter;
import utilpack.parsing.arguments.ArgumentParser;
import utilpack.parsing.arguments.ExpectedArgumentException;
import utilpack.parsing.arguments.ExpectedOptionParameterException;
import utilpack.parsing.arguments.UnexpectedArgumentException;

public class Transform {
	
	private static final Tuple labels = Tuple.ofStrings("File", "Scheduler", "Planner", "Bandwidth", "Nodes", "Simulation Time");
	
	//key: (simulation name, workflow name, nodes, bandwidth); value: (medium execution time)
	private static Map<Tuple, Double> results = new TreeMap<>();

	private static File resultsDir = new File("./sim-dataset/Sipht");
	private static File resultsFile = new File("./Sipht-times.csv");


	private static Set<String> daxFiles = new TreeSet<>();
	private static Set<Tuple> strategies = new HashSet<>();
	private static Set<Integer> bandwidths = new TreeSet<>();
	private static Set<Integer> numbersOfNodes = new TreeSet<>();
	private static Set<String> appNames = new TreeSet<>();
	private static Set<Integer> taskNums = new TreeSet<>();

	public static void main(String[] args) throws FileNotFoundException, IOException, ExpectedOptionParameterException, ExpectedArgumentException, UnexpectedArgumentException {
		Command.defaultDocs("", "Transforms collected simulations data to interesting datasets. This command allows to build the node-variation and the task-variation datasets.")
		.addRequiredArgument("results-file", "the CSV file containing the collected simulations data")
		.addOption(Option.create("output", "The directory where the output transformed results are stored")
				.addAlias("o")
				.addParameter("value", "directory path", resultsDir.toString())
				.build())
		.build(ctx->{
			resultsFile=new File(ctx.getArgument("results-file"));
			resultsDir=new File(ctx.getOption("output").get("value"));
		})
		.execute(args);

		System.out.println("Transform");
		resultsDir.mkdirs();
		
		try (CSVReader csvReader = new CSVReader(resultsFile, ';')){
			if(csvReader.hasNextTuple())
				csvReader.read();
			while(csvReader.hasNextTuple()) {
				Tuple tuple = csvReader.read();
				results.put(tuple.head(), Double.parseDouble(tuple.getLast().toString().replace(",", ".")));
			}
		}
		
		for(Tuple simKey : results.keySet()) {
			Iterator<Object> it = simKey.iterator();
			
			String daxFile = (String) it.next();
			Tuple strategy = Tuple.ofObjects((String) it.next(), (String) it.next());
			String bw = (String) it.next();
			String nodes = (String) it.next();
			
			daxFiles.add(daxFile);
			strategies.add(strategy);
			bandwidths.add(Integer.parseInt(bw));
			numbersOfNodes.add(Integer.parseInt(nodes));
			
			if(daxFile.matches(".+_[0-9]+(\\..*)?")) {
				String[] workflow = daxFile.split("\\.")[0].split("_");
				String appName = workflow[0];
				String taskNum = workflow[1];
				appNames.add(appName);
				taskNums.add(Integer.parseInt(taskNum));
			}
		}
		
		createNodeVariationDataset();
		createTaskVariationDataset();
	}
	
	static void createNodeVariationDataset() throws FileNotFoundException, IOException {
		// dataset id: (daxFile, bandwidth)
		// columns: nodes, matrix, albatross, workgiving
		Tuple labels = Tuple.ofObjects("nodes");
		for(Tuple s : strategies)
			labels = labels.add(s.<String>get(0));
		for(String appName : appNames)
			for(Integer taskNum : taskNums) 
				for(Integer bandwidth : bandwidths) {
					File csvFile = Paths.get(resultsDir.getName(), appName, "nodevar", "nodevar_"+appName+"_"+taskNum+".xml_bw"+bandwidth+".csv").toFile();
					csvFile.getParentFile().mkdirs();
					try(CSVWriter csvWriter = new CSVWriter(csvFile, ';', false)){
						csvWriter.write(labels);
						for(Integer nodes : numbersOfNodes) {
							List<String> tuple = new LinkedList<>();
							tuple.add(""+nodes);
							for(Tuple strategy : strategies) {
								Tuple simKey = Tuple.ofStrings(appName + "_"+taskNum+".xml", strategy.get(0), strategy.get(1), bandwidth, nodes);
								if(results.containsKey(simKey)) {
									double simTime = results.get(simKey);
									tuple.add(String.format("%1.3f",simTime));
								}
								else tuple.add("<unknown>");
							}
							Tuple datasetTuple = Tuple.ofStrings(tuple);
							csvWriter.write(datasetTuple);
						}
					}
				}
	}
	
	static void createTaskVariationDataset() throws FileNotFoundException, IOException {
		// dataset id: (appName, nodes, bandwidth)
		// columns: tasks, matrix, albatross, workgiving
		
		Tuple labels = Tuple.ofObjects("tasks");
		for(Tuple s : strategies)
			labels = labels.add(s.<String>get(0));
		
		
		for(String appName : appNames) {
			//System.out.println(appName);
			for(Integer nodes : numbersOfNodes) {
				for(Integer bandwidth : bandwidths) {
					File csvFile = Paths.get(resultsDir.getName(), appName, "taskvar", "taskvar_"+appName+"_nodes"+nodes+"_bw"+bandwidth+".csv").toFile();
					csvFile.getParentFile().mkdirs();
					try(CSVWriter csvWriter = new CSVWriter(csvFile, ';', false)){
						csvWriter.write(labels);
						for(Integer taskNum : taskNums) {
							//System.out.println(taskNum);
							String daxFile = appName+"_"+taskNum+".xml";
							if(daxFiles.contains(daxFile)) {
								List<String> tuple = new LinkedList<>();
								tuple.add(String.valueOf(taskNum));
								for(Tuple strategy : strategies) {
									Tuple simKey = Tuple.ofStrings(daxFile, strategy.get(0), strategy.get(1), bandwidth, nodes);
									if(results.containsKey(simKey)) {
										//System.out.println(simKey);
										double simTime = results.get(simKey);
										tuple.add(String.format("%1.3f",simTime));
									}
									else
										tuple.add("<unknown>");
								}
								Tuple datasetTuple = Tuple.ofStrings(tuple);
								csvWriter.write(datasetTuple);
							}
						}
					}
				}
			}
		}
	}

}
