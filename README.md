# CloudComputingAssignment4
## Benchmarking

// Creates a cluster on AWS EMR with default configurations set<br />
// We set the region to us-east<br />
// Set the location of our logs to an S3 bucket<br />
// Create 2 instances of m4.large in the cluster<br />
// Use the default Roles<br />
// Use a paswordless SSH key to connect<br />

**// Creates default emr roles<br />**
aws emr create-default-roles<br />
aws emr create-cluster --release-label emr-5.20.0 --applications Name=Flink --configurations file://./configuration.json --region us-east-1 --log-uri s3://cloudcomputingemr/logs --instance-type m4.large --instance-count 2 --service-role EMR_DefaultRole --ec2-attributes KeyName=CloudComputingAssignmentFourKey,InstanceProfile=EMR_EC2_DefaultRole
<br />
<br />
**// Create an S3 bucket for our configuration files (input, output)**<br />
aws s3 mb cloudcomputingemr<br />

**// Copy our data to the bucket<br />**
aws s3 cp opencellid_data s3://cloudcomputingemr --recursive<br />
aws s3 cp tolstoy-war-and-peace.txt s3://cloudcomputingemr<br />

**// Testing our jars on local machine<br />**
 ./bin/flink run ../CCAssignmentFourMaven/out/artifacts/CCAssignmentFourMavenKMeans/CCAssignmentFourMavenKMeans.jar --input ../CCAssignmentFourMaven/opencellid_data/berlin.csv --iterations 10 --k 500  --output clusters.csv
<br />
<br />
**// Add a new step to our cluster**<br />
**// Runs a specific jar file using the command-runner that is installed by default on the cluster<br />**
**// Specifies jar file to run (this file needs to be on the cluster locally -> no S3 links allowed here)<br />**
**// Specifies parameters to pass to main method of jar file<br />**
**// Specifies cluster id and gives a name for the step<br />**
**// Sets in which region the cluster is located<br />**
aws emr add-steps --cluster-id j-27VCHJIVA0MQW --steps Type=CUSTOM_JAR,Name=WordCountStep,Jar=command-runner.jar,Args="flink","run","-m","yarn-cluster","-yn","2","/home/hadoop/CCAssignmentFourMaven.jar","--input","s3://cloudcomputingemr/tolstoy-war-and-peace.txt","--output","s3://cloudcomputingemr/word-output.csv" --region us-east-1
<br />
<br />
**// This deploys the clustering script as well**<br />
**// For benchmarking we tweaked the parameters "k" and "iterations"**<br />
**// Ran the step multiple times and measured results**<br />
aws emr add-steps --cluster-id j-27VCHJIVA0MQW --steps Type=CUSTOM_JAR,Name=ClusterBerlinStep,Jar=command-runner.jar,Args="flink","run","-m","yarn-cluster","-yn","2","/home/hadoop/CCAssignmentFourMavenKMeans.jar","--input","s3://cloudcomputingemr/berlin.csv ","--output","s3://cloudcomputingemr/cluster-output.csv","--iterations","10","--k","500" --region us-east-1




### Cluster configuration:
- m4.large<br/ >
- 2 instances<br/ >
- us-east<br/ >

### Results: 
#1: 
- 10 iterations, 500 clusters 
- 29809 ms runtime
#2: 
- 50 iterations, 500 clusters 
- 45152 ms runtime
#3: 
- 250 iterations, 500 clusters 
- 109869 ms runtime
#4: 
- 10 iterations, 50 clusters 
- 25061 ms runtime
#5: 
- 10 iterations, 250 clusters 
- 26291 ms runtime


### Observations: 
- Changing number of iterations greatly increases the runtime
	- This results in higher workload on all nodes in the cluster which in turn results in longer runtime
	- This could be reduced by adding more nodes
	- The actual result doesn't change (get better/improve) significantly after a certain number (treshold) of iterations is reached
- Changing the number of clusters doesn't increase the runtime significantly
	- Increased number of clusters results in a negligible increase due to the increase of the communication between workers effort
	- Work distributed more efficiently than in the case of just an increased number of iterations



### Questions: 
**--Which steps in your program require communication and synchronization between your workers?**

-WordCount: Communication is required when combing intermediate results of MapReduce. Tuples containing the same words from different workers have to be reduced before the final output. 

-CellCluster: The position of a centroid changes based on the distibution of the points. If a dataset is split into parts processed by different worker nodes, each worker could also adjust the position of a centroid and would have to communicate it to the other workers.

**--What resources are your programs bound by? Memory? CPU? Network? Disk?**

-WordCount: This program is mainly bound by memory because it needs to temprorarily store every distinct word and its count during execution. Texts that have richer vocabulary (more distinct words) would need more storage to keep track of them. 

-CellCluster: The main resources that our programs are bound by are CPU and Network. CPU is required to process the tasks - and if those get heavy, then CPU becomes a bottleneck on the node-level. Network could become a bottleneck when there is lot of synchronization needed and the processing slows down due to the limited bandwidth and increased throughput between the nodes.  


**--Could you improve the partitioning of your data to yield better run-time?**

-WordCount: We could improve partitioning by dividing the data by chapters - this could improve speed as the probability of finding the same words are higher in the same chapter.

-CellCluster: We could partition by the latitude and longitude. Split the data into regions based on which the worker tasks would be divided. The less workers work on the same region, the less communication between the workers is required. However, this might result in worse performance for nodes processing points that would end up at borders of the coverage map regions
