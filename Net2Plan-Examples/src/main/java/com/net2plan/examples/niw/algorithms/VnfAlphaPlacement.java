package com.net2plan.examples.niw.algorithms;

import com.google.common.collect.Lists;
import com.net2plan.interfaces.networkDesign.IAlgorithm;
import com.net2plan.interfaces.networkDesign.Link;
import com.net2plan.interfaces.networkDesign.Net2PlanException;
import com.net2plan.interfaces.networkDesign.NetPlan;
import com.net2plan.libraries.GraphUtils;
import com.net2plan.niw.*;
import com.net2plan.utils.InputParameter;
import com.net2plan.utils.Pair;
import com.net2plan.utils.Triple;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

	public class VnfAlphaPlacement implements IAlgorithm
	{
		public Map<String, List<String>> directLightpaths = new HashMap<>();
		
		@Override
		public String executeAlgorithm(NetPlan netPlan, Map<String, String> algorithmParameters, Map<String, String> net2planParameters)
		{

			final Double Lmax = Double.parseDouble(algorithmParameters.get ("Lmax"));
			final int K = Integer.parseInt(algorithmParameters.get ("K"));
			final int numServices = Integer.parseInt(algorithmParameters.get ("numServices"));
			final String excelFile = algorithmParameters.get("excelFile");
			final double alpha = Double.parseDouble(algorithmParameters.get("alpha"));
			final int seed = Integer.parseInt(algorithmParameters.get("seed"));
			final boolean VNFsInMCENb = Boolean.parseBoolean(algorithmParameters.get("VNFsInMCENb"));
			final boolean concentration = Boolean.parseBoolean(algorithmParameters.get("concentration"));
			
			//First of all, initialize all parameters
			InputParameter.initializeAllInputParameterFieldsOfObject(this, algorithmParameters);
						
			File excelPath = new File(excelFile);
			WNet wNet = ImportMetroNetwork.importFromExcelFile(excelPath);		
			netPlan.copyFrom(wNet.getNetPlan());
			wNet = new WNet(netPlan);
			final OpticalSpectrumManager osm = OpticalSpectrumManager.createFromRegularLps(wNet);
			
			double linerate_Gbps = 10;
			int slotsPerLightpath = 4;
			int Tmax_Mbps = 10000;

			//Perform here initial checks

			/* Remove any existing lightpath, VNF instances and any existing service chain */
			for(WLightpathRequest lpr : wNet.getLightpathRequests())
				lpr.remove(); // this removes also the lps

			for(WVnfInstance vnf : wNet.getVnfInstances())
				vnf.remove();

			for(WServiceChain sc : wNet.getServiceChains())
				sc.remove();
			
			
			/* Create Services and VNFs*/
			//SortedMap<String , WUserService> newInfo = wNet.getUserServicesInfo();
			for(int i=0; i<numServices; i++) 
			{
				int numberOfVNFsInThisService = (int) randomWithRange(1,5); //is the minimum 0 or 1?
				List<String> VNFSUpstream = new ArrayList<String>();
				for(int j=0; j<numberOfVNFsInThisService; j++) 
				{
					
					WVnfType vnfType = new WVnfType("VNF"+j+"_Service"+i, 	//Name
		        									100000000, 				//Capacity_Gbps
		        									2, 						//CPU
		        									4, 						//RAM
		        									20, 					//HD
		        									1.0 , 					//Processing Time
		        									Optional.empty(), 		//Instantiation nodes
		        									"");					//Arbitrary Params
		        	wNet.addOrUpdateVnfType(vnfType);
		        	

		        	VNFSUpstream.add("VNF"+j+"_Service"+i);
				}
				List<String> VNFSDownstream = Lists.reverse(VNFSUpstream);
				List<Double> TrafficExpansion = Collections.nCopies(numberOfVNFsInThisService, 1.0);
				List<Double> latency = Collections.nCopies(numberOfVNFsInThisService+1, randomWithRange(0.5,Lmax));
				System.out.println("Service"+i+" latency is: "+latency.get(0)+ " and it has "+VNFSUpstream.size()+" VNFs");
				
	        	WUserService userService = new WUserService("Service"+i, 		//Name
	        												VNFSUpstream,		//VNF Upstream
	        												VNFSDownstream,		//VNF Downstream
	        												TrafficExpansion,	//Traffic expansion Up
	        												TrafficExpansion,	//Traffic expansion Down
	        												latency,			//Max Latency Up
	        												latency,			//Max Latency Down
	        												1.0, 				//Injection expansion
	        												false, 				//Ending in core node?
	        												"");				//Arbitrary params
	        	wNet.addOrUpdateUserService(userService);
			}
			
			/* Adding lightpaths */
			for (WNode origin : wNet.getNodes()) {
				for (WNode destination : wNet.getNodes()) {
					if (!origin.equals(destination)) {	
						
						List<List<WFiber>> cpl = wNet.getKShortestWdmPath(K, origin, destination, Optional.empty());
						if(cpl.get(0).size() == 1) { // adjacent nodes
							List<WFiber> uniqueFiber = cpl.get(0);
							//if (wNet.getLightpaths().stream().filter(l->l.getA() == destination && l.getB() == origin).count() > 0) continue;
							WLightpathRequest lpr = wNet.addLightpathRequest(origin, destination, linerate_Gbps,false);
							Optional<SortedSet<Integer>> wl = osm.spectrumAssignment_firstFit(uniqueFiber,slotsPerLightpath, Optional.empty());
							if (wl.isPresent()) {
								WLightpath lp = lpr.addLightpathUnregenerated(uniqueFiber, wl.get(), false);
								osm.allocateOccupation(lp, uniqueFiber, wl.get());
								Pair<WIpLink,WIpLink> ipLink = wNet.addIpLinkBidirectional(origin, destination, linerate_Gbps);
								lpr.coupleToIpLink(ipLink.getFirst());
							}
						}

					}
				}
			}
			
			/* ############################# ALGORITMO ############################# */
			System.out.println("####################### STARTING ALGORITHM #######################");
			WNode greatestNode = getGreatestNode(wNet); //Necessary in order to calculate the traffic per service
			System.out.println("The greatest node is "+greatestNode.getName()+" with "+ greatestNode.getPopulation()+" people");
			
			
			List<WNode> shuffledNodesList = wNet.getNodes();
			Collections.shuffle(shuffledNodesList);
			int counter = 0;
			for(WNode node : shuffledNodesList) 
			{
				for(WUserService service : wNet.getUserServicesInfo().values()) 
				{
					System.out.println("---------------------------------------------------------------");
					System.out.println("Iteration number: "+ ++counter);
					System.out.println("Iteration "+node.getName() +" & "+ service.getUserServiceUniqueId());
				
					double serviceTraffic_Mbps = node.getPopulation()*Tmax_Mbps/greatestNode.getPopulation();
					double serviceLatency_ms = service.getListMaxLatencyFromInitialToVnfStart_ms_upstream().get(0);
					System.out.println(service.getUserServiceUniqueId()+" latency: "+truncate(serviceLatency_ms,2));
					
					WNode coreNode = getNearestCoreNode(netPlan, wNet, node).getAsNode();
					System.out.println("Core Node selected: "+coreNode.getName());
					System.out.println(node.getName()+" -> "+coreNode.getName());
					
					WServiceChainRequest serviceChainRequest = wNet.addServiceChainRequest(node, true, service);
					final List<String> vnfsToTraverse = serviceChainRequest.getSequenceVnfTypes();
					
					System.out.println("### Number of VNFs for service "+service.getUserServiceUniqueId()+": "+service.getListVnfTypesToTraverseUpstream().size());
				
					if(VNFsInMCENb) {
						System.out.println("-------------------------------------- Adding VNFs to core node --------------------------------------");
						List<String> serviceVNFS = service.getListVnfTypesToTraverseUpstream();
						for(String vnfName : serviceVNFS) wNet.addVnfInstance(coreNode, vnfName, wNet.getVnfType(vnfName).get());

						List<List<WAbstractNetworkElement>> paths = wNet.getKShortestServiceChainInIpLayer(K, node, coreNode, vnfsToTraverse, Optional.empty(), Optional.empty());
						System.out.println("Path size: "+paths.get(0).size());
						
						serviceChainRequest.addServiceChain(paths.get(0),serviceTraffic_Mbps/1000);
						System.out.println("All VNFs instantiated in "+coreNode.getName());
					}else 
					{
						int nVNFsInShortestPath = (int) Math.ceil(alpha * service.getListVnfTypesToTraverseUpstream().size()); 
						int nVNFsRandom = service.getListVnfTypesToTraverseUpstream().size() - nVNFsInShortestPath;
						System.out.println("### Number of VNFs to instantiate in the shortest path (alpha = "+alpha+"): "+nVNFsInShortestPath);
						
						//first, if the node is a CoreNode the VNFS must be instantiated on it. It's the best solution possible.
						//Case 1: The node is Core Node
						if(coreNode.equals(node)) {
							System.out.println("Case 1: The node is a Core Node!!");
							System.out.println("Result: "+service.getUserServiceUniqueId()+" resources allocated in "+node.getName());
							List<String> serviceVNFS = service.getListVnfTypesToTraverseUpstream();
							for(String vnfName : serviceVNFS) wNet.addVnfInstance(node, vnfName, wNet.getVnfType(vnfName).get());

							final List<List<WAbstractNetworkElement>> paths = wNet.getKShortestServiceChainInIpLayer(K, node, node, vnfsToTraverse, Optional.empty(), Optional.empty());
							serviceChainRequest.addServiceChain(paths.get(0),serviceTraffic_Mbps/1000);
	
						}else {
							
							List<List<WFiber>> kFiberLists = wNet.getKShortestWdmPath(K, node, coreNode, Optional.empty());
							List<WFiber> fiberListk0 = kFiberLists.get(0);
							
							int numberOfHops = fiberListk0.size();
							
							// know nodes in shortest path as List<WNode>
							List <WNode> nodesInShortestPath = getNodesInPath(fiberListk0);
							
							// VNFs allocation in the destination node of the SP.
							List<String> serviceVNFS = new ArrayList<String>();
							serviceVNFS = service.getListVnfTypesToTraverseUpstream();
							for (int i=0; i<nVNFsInShortestPath; i++) {
								String vnfName = serviceVNFS.get(serviceVNFS.size()-1 - i);
								wNet.addVnfInstance(coreNode, vnfName, wNet.getVnfType(vnfName).get());
							}
							
							// Know valid nodes to instantiate VNFs out of the shortest path List<WNode>
							if(nVNFsInShortestPath != service.getListVnfTypesToTraverseUpstream().size()) 
							{	
								List<WNode> candidateNodesOutOfTheSP = getNodesInRangeOutOfTheShortestPath(wNet, node, numberOfHops, nodesInShortestPath);
							
								if(concentration)
								{
									System.out.println("Instantiating VNFs depending on the previous concentration..");
									List<Integer> VNFsInNodes = new ArrayList<Integer>();
									for (WNode candidateNode : candidateNodesOutOfTheSP) {
										VNFsInNodes.add(candidateNode.getVnfInstances().size());
									}
									WNode nodeToInstantiateVNF = candidateNodesOutOfTheSP.get(VNFsInNodes.indexOf(Collections.max(VNFsInNodes)));
									for(int i=0; i<nVNFsRandom; i++) {
										String vnfName = serviceVNFS.get(i);
										wNet.addVnfInstance(nodeToInstantiateVNF, vnfName, wNet.getVnfType(vnfName).get());
									}
								}else
								{
									System.out.println("Instantiating VNFs randomly..");
									// Set Service VNFs randomly in a candidate node.
									Random rng = new Random (seed);		
									for(int i=0; i<nVNFsRandom; i++) {
										String vnfName = serviceVNFS.get(i);
										wNet.addVnfInstance(candidateNodesOutOfTheSP.get(rng.nextInt(candidateNodesOutOfTheSP.size())), vnfName, wNet.getVnfType(vnfName).get());
									}
								}
							}
							
							// IP Layer
							final List<List<WAbstractNetworkElement>> paths = wNet.getKShortestServiceChainInIpLayer(K, node, node, vnfsToTraverse, Optional.empty(), Optional.empty());
							serviceChainRequest.addServiceChain(paths.get(0),serviceTraffic_Mbps/1000);
						}
					}
				}
			}
			
			/* ############################# */
			System.out.println("############################################################################");
			System.out.println("LIGHTPATHS");
			
			List<WLightpath> lpList = wNet.getLightpaths();
			System.out.println("Number of lightpaths: "+lpList.size());
			for(WLightpath lp : lpList) System.out.println(lp.getA().getName()+" -> "+lp.getB().getName());

			/* Dimension the VNF instances, consuming the resources CPU, HD, RAM */
			/*
			 * for(WVnfInstance vnf : wNet.getVnfInstances())
			 * vnf.scaleVnfCapacityAndConsumptionToBaseInstanceMultiple();
			 */

			// HDD 	0.0425€/GB	(85€ 	-> 		2000GB)
			// CPU	35€/Core	(70€	->		2 Cores)
			// RAM 	12.5€/GB	(50€	->		4GB)
			int finalCost = 0;
			
			//COMPUTE FINAL COST
			for(WNode node : wNet.getNodes()) {
				finalCost += Math.sqrt((85/2000)*node.getOccupiedHdGB()+(70/2)*node.getOccupiedCpus()+(50/4)*node.getOccupiedRamGB());
			}
			
			final File folder = new File (Lmax.toString());
			if (folder.exists() && folder.isFile()) throw new Net2PlanException ("The folder is a file");
			if (!folder.exists()) folder.mkdirs();
			
			//SORTED BY NAME
			final List<String> summaryString = new ArrayList<> ();
			summaryString.add(finalCost + "");
			for(WNode node : wNet.getNodes()) {		
				summaryString.add(node.getName() + "");
				summaryString.add(node.getOccupiedCpus() + "");
				summaryString.add(node.getOccupiedRamGB() + "");
				summaryString.add(node.getOccupiedHdGB() + "");
			}
			writeFile (new File (folder , "sortedByName" + 0  + ".txt") , summaryString);
			
			//SORTED BY POPULATION
			summaryString.clear();
			summaryString.add(finalCost + "");

			Comparator<WNode> comparator = new Comparator<WNode>() {
			    @Override
			    public int compare(WNode A, WNode B) {
			        return (int) (B.getPopulation() - A.getPopulation());
			    }
			};
			
			List<WNode> nodesSortedByPopulation = wNet.getNodes();
			Collections.sort(nodesSortedByPopulation, comparator); 
			
			for(WNode node : nodesSortedByPopulation) {
				summaryString.add(node.getName() + "");
				summaryString.add(node.getOccupiedCpus() + "");
				summaryString.add(node.getOccupiedRamGB() + "");
				summaryString.add(node.getOccupiedHdGB() + "");
			}
			writeFile (new File (folder , "sortedByPopulation" + 0  + ".txt") , summaryString);
			

			return "Ok";
		}
		

		@Override
		public String getDescription()
		{
			return null;
		}

		@Override
		public List<Triple<String, String, String>> getParameters()
		{
			final List<Triple<String, String, String>> param = new LinkedList<Triple<String, String, String>> ();
			param.add(Triple.of ("VNFsInMCENb" , "false" , "True if all VNFs will be instantiated in MCENb, false if not."));
			param.add(Triple.of ("concentration" , "false" , "True if concentration nodes will be created, false if not."));
			param.add (Triple.of ("Lmax" , "5.0" , "Maximum latency value"));
			param.add (Triple.of ("K" , "5" , "Number of shortest paths to evaluate"));
			param.add (Triple.of ("numServices" , "50" , "Number of services"));
			param.add (Triple.of ("excelFile" , "MHTopology_Nodes_Links_95%.xlsx" , "Selection of the Excel spreadsheet"));
			param.add (Triple.of ("alpha" , "0" , "Factor that determines the number of VNFs in the shortest path"));
			param.add (Triple.of ("seed" , "1" , "Seed to generate random numbers"));

			return param;
		}
		
		public List<WNode> getNodesInPath(List<WFiber> fiberList){
			List<WNode> nodeList = new ArrayList<WNode>();
					
			Iterator it = fiberList.iterator();
			while(it.hasNext()) {
				WFiber fiber = (WFiber) it.next();
				nodeList.add(fiber.getA());
			}
			return nodeList;
		}
		
		
		public double randomWithRange(double min, double max)
		{
			double range = (max - min);
			return (Math.random() * range) + min;
		}
		
		public void setServiceVNFSInNode(WNet wNet, List<String> serviceVNFS, WNode endingNode, int numberOfVNFsToInstantiate) {
			
			for (int i=0; i<numberOfVNFsToInstantiate; i++) {
				String vnfName = serviceVNFS.get(serviceVNFS.size()-1 - i);
				wNet.addVnfInstance(endingNode, vnfName, wNet.getVnfType(vnfName).get());
			}
		}
		
		public void setServiceVNFSRandomnlyInNodeRange(WNet wNet, List<String> serviceVNFS, List<WNode> validNodes, int numberOfVNFsToInstantiate, Random rng) 
		{
			int numberOfVNFSAlreadyInstantiated = serviceVNFS.size() - numberOfVNFsToInstantiate;
			for (int i=0; i<numberOfVNFSAlreadyInstantiated; i++) serviceVNFS.remove(i); //delete VNFs already instantiated
			
			for(int i=0; i<serviceVNFS.size(); i++) {
					String vnfName = serviceVNFS.get(i);
					wNet.addVnfInstance(validNodes.get(rng.nextInt(validNodes.size())), vnfName, wNet.getVnfType(vnfName).get());
			}

			
		}
		
		public List<WNode> getNodesInRangeOutOfTheShortestPath(WNet wNet, WNode originNode, int maxNumberOfHops, List<WNode> nodesInShortestPath){
			List <WNode> validNodes = new ArrayList<WNode>();
			
			for(WNode node : wNet.getNodes()) 
			{
				if( !node.equals(originNode) ) {
					List<List<WFiber>> o2nFiberLists = wNet.getKShortestWdmPath(1, originNode, node, Optional.empty());
					List<WFiber> o2nFiberListK0 = o2nFiberLists.get(0);					
					if( o2nFiberListK0.size() <= maxNumberOfHops && !nodesInShortestPath.contains(node) ) validNodes.add(node);
				}

			}
			
			return validNodes;
		}
		
		public WNode getGreatestNode(WNet wNet) {
			
			List<WNode> nodesList = wNet.getNodes();
			WNode greatestNode = null;
			double maxPopulation = 0;
			
			Iterator it = nodesList.iterator();
			
			while(it.hasNext()) {
				WNode node = (WNode) it.next();
				
				if(node.getPopulation() > maxPopulation) {
					maxPopulation = node.getPopulation();
					greatestNode = node.getAsNode();
				}
			}
			
			return greatestNode;
		}
	
		public WNode getNearestCoreNode(NetPlan netPlan, WNet wNet, WNode node) {
			int minStepsBetweenNodes = Integer.MAX_VALUE;
			WNode nearestCoreNode = null;
			Iterator it = wNet.getNodesConnectedToCore().iterator();
			while(it.hasNext()) {
				WNode coreNode = (WNode) it.next();
				int currentStepsBetweenNodes = GraphUtils.getShortestPath(netPlan.getNodes(), netPlan.getLinks(), node.getNe(), coreNode.getNe(), null).size();
				if(minStepsBetweenNodes > currentStepsBetweenNodes)
				{
					minStepsBetweenNodes = currentStepsBetweenNodes;
					nearestCoreNode = coreNode;
				}
			}
			
			return nearestCoreNode;
		}
		
		static double truncate(double value, int places) {
		    return new BigDecimal(value)
		        .setScale(places, RoundingMode.DOWN)
		        .doubleValue();
		}
		
		public boolean existsDirectLightpath(String node, String endingNode) {
			if(directLightpaths.containsKey(node)) 
			{ 
				if(directLightpaths.get(node).contains(endingNode)) {
					System.out.println("Exists direct lightpath "+ node+" -> "+endingNode);
					return true;
				} else {
					return false;
				}
				
			}else{
				return false;
			}
		}
		
		public Map<Integer, Integer> getBestPathInLatency(WNet wNet, List<List<WFiber>> kFiberLists) {
			
			int hops = Integer.MAX_VALUE;
			double finalLatency = Double.MAX_VALUE;
			WNode A = null;
			int currentK = -1;
			int finalK = -1;
			
			
			Iterator it1 = kFiberLists.iterator();
			while(it1.hasNext()) {
				List<WFiber> fiberList = (List<WFiber>) it1.next();
				Iterator it2 = fiberList.iterator();
				currentK++;
				boolean ok = true;
				
				while(it2.hasNext() && ok) {

					WFiber fiber = (WFiber) it2.next();
					String endingNode = fiberList.get(fiberList.size()-1).getB().getName();
					System.out.println(fiber.getA().getName()+" ? "+endingNode);
					if(existsDirectLightpath(fiber.getA().getName(), endingNode))
					{
					 System.out.println("Match");
					 hops = fiberList.indexOf(fiber) + 1;
					 double currentLatency = wNet.getPropagationDelay(fiberList) + 2*0.1*hops;
					 if(currentLatency < finalLatency) {
						 finalLatency = currentLatency;
						 A = fiber.getA().getAsNode();
						 ok = false;
						 finalK = currentK;
					 }
					// break;
					}
				}
			}
			return new HashMap<Integer, Integer>();
		}
		
		private static void writeFile (File file , List<String> rows)
		{
			PrintWriter of = null;
			try 
			{ 
				of = new PrintWriter (new FileWriter (file));
				for (String row : rows)
					of.println(row);
				of.close();
			} catch (Exception e) { e.printStackTrace(); if (of != null) of.close(); throw new Net2PlanException ("File error"); }
			
		}
		
		public static void main (String[]args) {
			List<Integer> test = new ArrayList<Integer>();
			
			test.add(10);
			test.add(11);
			test.add(11);
			
			System.out.println("The maximum value is "+Collections.max(test)+" with index "+test.indexOf(Collections.max(test)+"."));
			
			/*System.out.println("###");
			System.out.println(test.contains("Que"));
			System.out.println(test.contains("Quea"));
			System.out.println("###");
			
			for(String st : test) System.out.println(st);*/
			}
	}
