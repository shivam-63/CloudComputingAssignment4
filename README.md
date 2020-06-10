# CloudComputingAssignment4

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
