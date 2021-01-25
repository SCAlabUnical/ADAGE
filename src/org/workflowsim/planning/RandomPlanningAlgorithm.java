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
package org.workflowsim.planning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.cloudbus.cloudsim.Vm;
import org.workflowsim.CondorVM;
import org.workflowsim.FileItem;
import org.workflowsim.Task;
import org.workflowsim.utils.Parameters;
import org.workflowsim.utils.ReplicaCatalog;

/**
 * The Random planning algorithm. This is just for demo. It is not useful in practice.
 *
 * @author Weiwei Chen
 * @since WorkflowSim Toolkit 1.0
 * @date Jun 17, 2013
 */
public class RandomPlanningAlgorithm extends BasePlanningAlgorithm {
	public static Random random;

    /**
     * The main function
     */
    @Override
    public void run() {
    	if(random == null) {
			String seed = System.getProperty("simulation.seed");
			
			if(seed != null) {
				try {
					random = new Random(Long.parseLong(seed));
				} catch (NumberFormatException e) {
				}
			}
			
			if(random == null)
				random = new Random();
		}
    	
    	Map<Integer, Integer> countByVm = new HashMap<>();
    	Map<String, List<String>> files = new HashMap<>();
    	
        for (Iterator it = getTaskList().iterator(); it.hasNext();) {
            Task task = (Task) it.next();
            
            int vmNum = getVmList().size();
            
            for(FileItem file : task.getFileList()) {
            	List<String> vms = ReplicaCatalog.getStorageList(file.getName());
            	if(vms != null)
	            	for(String vm : vms) {
	                    files.computeIfAbsent(vm, k->new ArrayList<>());
	                    files.get(vm).add(file.getName());
	            	}
            }
            /**
             * Randomly choose a vm
             */
            
            int vmId = random.nextInt(vmNum);
            
            CondorVM vm = (CondorVM) getVmList().get(vmId);
            //This shows the cpu capability of a vm
            double mips = vm.getMips();
            
            task.setVmId(vm.getId());
            countByVm.compute(vm.getId(), (k,v)->v==null? 1 : v+1);
                    
            long deadline = Parameters.getDeadline();

        }
//        System.out.println("vm=files");
//        System.out.println(files);
//        System.out.println("vm=task-count");
//        System.out.println(countByVm);
    }


}
