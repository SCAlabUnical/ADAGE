package simulation;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Map.Entry;
import java.util.TreeMap;

import readycli.Command;
import utilpack.Tuple;
import utilpack.parsing.CSVReader;
import utilpack.parsing.CSVWriter;

public class TaskCompletionTransform {
	static File file = new File("./log/tasks-ALBATROSS.RANDOM.nodes_128.bw_1000.file_Montage_10000.xml.csv");
	
	public static void main(String[] args) throws IOException {
		//args = new String[] {"./log/tasks-ALBATROSS.RANDOM.nodes_128.bw_1000.file_Montage_10000.xml.csv"};
		args = new String[] {"./log/tasks-MATRIX.RANDOM.nodes_128.bw_1000.file_Montage_10000.xml.csv"};
		//args = new String[] {"./log/tasks-WORKGIVING.CRITICAL_PATH.nodes_128.bw_1000.file_Montage_10000.xml.csv"};
		
		Command.defaultDocs("", "TaskCompletionTransform - Aggregates the time values of a task completion csv file")
					.addRequiredArgument("file", "The input file")
					.build(ctx->{
						file = new File(ctx.getArgument("file"));
					}).execute(args);
		
		TreeMap<Double, Integer> reduceMap = new TreeMap<>();
		
		System.out.println("Loading data...");
		try(CSVReader csv = new CSVReader(file, ';')){
			if(csv.hasNextTuple())
				csv.read(); // LABELS row
			while(csv.hasNextTuple()) {
				Tuple tuple = csv.read();
				Double time = Double.parseDouble(tuple.<String>get(0).replaceAll(",", "."));
				Integer tasks = Integer.parseInt(tuple.<String>get(1));
				reduceMap.compute(time, (t, v)->v==null?tasks : Math.max(v, tasks));
			}
		}
		
		System.out.println("Data:");
		for(Entry<Double, Integer> e : reduceMap.entrySet()) 
			System.out.println(e);

		
		System.out.println("Writing data...");
		try(CSVWriter csv = new CSVWriter(file, ';', false)){
			csv.write(Tuple.ofObjects("time","tasks"));
			for(Entry<Double, Integer> e : reduceMap.entrySet()) {
				csv.write(Tuple.ofObjects(
						String.format("%1.3f", e.getKey()), 
						e.getValue().toString()
					));
			}
		}
		
		System.out.println("Transformation finished.");
	}
}
