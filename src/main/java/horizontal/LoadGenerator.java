 package horizontal;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.AmazonEC2Exception;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Reservation;
import com.amazonaws.services.ec2.model.CreateSecurityGroupRequest;
import com.amazonaws.services.ec2.model.CreateSecurityGroupResult;
import com.amazonaws.services.ec2.model.TerminateInstancesRequest;
import com.amazonaws.services.ec2.model.AuthorizeSecurityGroupIngressResult;
import com.amazonaws.services.ec2.model.AuthorizeSecurityGroupIngressRequest;
import com.amazonaws.services.ec2.model.IpPermission;
import com.amazonaws.services.ec2.model.IpRange;
import com.amazonaws.services.ec2.model.RunInstancesResult;
import com.amazonaws.services.ec2.model.RunInstancesRequest;
import com.amazonaws.services.ec2.model.CreateTagsRequest;
import com.amazonaws.services.ec2.model.CreateTagsResult;
import com.amazonaws.services.ec2.model.Tag;
import com.amazonaws.services.ec2.model.DeleteSecurityGroupRequest;
import com.amazonaws.services.ec2.model.DeleteSecurityGroupResult;
import com.amazonaws.services.ec2.model.DescribeInstanceStatusRequest;
import com.amazonaws.waiters.WaiterParameters;
// import com.amazonaws.services.ec2.waiters;

import org.apache.commons.io.FileUtils;
import org.ini4j.Ini;
import utilities.Configuration;
import utilities.HttpRequest;


import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.Date;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;

/**
 * Class for Task1 Solution.
 */
public final class LoadGenerator {
    /**
     * Project Tag value.
     */
    public static final String PROJECT_VALUE = "2.1";

    /**
     * Configuration file.
     */
    static final Configuration CONFIGURATION
            = new Configuration("horizontal-scaling-config.json");

    /**
     * Load Generator AMI.
     */
    private static final String LOAD_GENERATOR
            = CONFIGURATION.getString("load_generator_ami");
    /**
     * Web Service AMI.
     */
    static final String WEB_SERVICE
            = CONFIGURATION.getString("web_service_ami");

    /**
     * Instance Type Name.
     */
    private static final String INSTANCE_TYPE
            = CONFIGURATION.getString("instance_type");
    /**
     * Security Key Name (e.g. pem).
     */
    // private static final String KEY_NAME
    private static final String KEY_NAME = "CCProject0";
    /**
     * Web Service Security Group Name.
     */
    static final String WEB_SERVICE_SECURITY_GROUP =
            "web-service-security-group";
    /**
     * Load Generator Security Group Name.
     */
    static final String LG_SECURITY_GROUP =
            "lg-security-group";
    /**
     * HTTP Port.
     */
    private static final Integer HTTP_PORT = 80;
    /**
     * Launch Delay in milliseconds.
     */
    private static final long LAUNCH_DELAY =  100000;
    /**
     * RPS target to stop provisioning.
     */
    private static final float RPS_TARGET = 50;
    /**
     * Submission Password.
     */
    private static final String SUBMISSION_PASSWORD
            = System.getenv("TPZ_PASSWORD");
    /**
     * TPZ_USERNAME
     */
    private static final String TPZ_USERNAME
            = System.getenv("TPZ_USERNAME");
    /**
     * Delay before retrying API call.
     */
    public static final int RETRY_DELAY_MILLIS = 100;

    private static final String vpcId = "vpc-76de210b";
    /**
     * Private Constructor.
     */
    private LoadGenerator() {
    }

    /**
     * Task1 main method.
     * @param args No Args required
     * @throws Exception when something unpredictably goes wrong.
     */
    public static void main(final String[] args) throws Exception {
        // BIG PICTURE TODO: Provision resources to achieve horizontal scalability
        //  - Create security groups for Load Generator and Web Service
        //  - Provision a Load Generator instance
        //  - Provision a Web Service instance
        //  - Register Web Service DNS with Load Generator
        //  - Add Web Service instances to Load Generator 
        //  - Terminate resources

        AWSCredentialsProvider credentialsProvider =
                new DefaultAWSCredentialsProviderChain();

        // Create an Amazon EC2 Client
        AmazonEC2 ec2 = AmazonEC2ClientBuilder
                .standard()
                .withCredentials(credentialsProvider)
                .withRegion(Regions.US_EAST_1)
                .build();

        // Create Security Groups
        try {
            createHttpSecurityGroup(ec2, WEB_SERVICE_SECURITY_GROUP);
        } catch (AmazonEC2Exception e) {
            System.out.println("Security group already exists");
        }

        try {
            createHttpSecurityGroup(ec2, LG_SECURITY_GROUP);
        } catch (AmazonEC2Exception e) {
            System.out.println("Security group already exists");
        }

        //  Create Load Generator instance and obtain DNS
        ArrayList<String> instanceIdArray = new ArrayList<>();

        RunInstancesRequest runLoadGeneratorRequest = new RunInstancesRequest();

        runLoadGeneratorRequest.withImageId(LOAD_GENERATOR)
                                .withInstanceType(INSTANCE_TYPE)
                                .withMinCount(1)
                                .withMaxCount(1)
                                .withKeyName(KEY_NAME)
                                .withSecurityGroups(LG_SECURITY_GROUP);

        RunInstancesResult loadGeneratorResult = ec2.runInstances(runLoadGeneratorRequest);
        //  TODO wait for the state to transit
        
        String loadGeneratorInstanceId = loadGeneratorResult
                                        .getReservation()
                                        .getInstances()
                                        .get(0)
                                        .getInstanceId();

        instanceIdArray.add(loadGeneratorInstanceId);

        //  Create first Web Service instance and obtain DNS
        RunInstancesRequest runWebServiceRequest = new RunInstancesRequest();

        runWebServiceRequest.withImageId(WEB_SERVICE)
                                .withInstanceType(INSTANCE_TYPE)
                                .withMinCount(1)
                                .withMaxCount(1)
                                .withKeyName(KEY_NAME)
                                .withSecurityGroups(WEB_SERVICE_SECURITY_GROUP);

        RunInstancesResult webServiceResult = ec2.runInstances(runWebServiceRequest);

        String webServiceInstanceId = webServiceResult
                                        .getReservation()
                                        .getInstances()
                                        .get(0)
                                        .getInstanceId();

        instanceIdArray.add(webServiceInstanceId);
        Instance loadGenerator = getInstance(ec2, loadGeneratorInstanceId);

        while(!loadGenerator.getState().getName().equals("running")) {
            Thread.sleep(800); // sleep < 1 sec
            loadGenerator = getInstance(ec2, loadGeneratorInstanceId);
        }

        Instance webServerInstance = getInstance(ec2, webServiceInstanceId);

        while(!webServerInstance.getState().getName().equals("running")) {
            Thread.sleep(800); // sleep < 1 sec
            webServerInstance = getInstance(ec2, webServiceInstanceId);
        }
        // Thread.sleep(120000);
        // TODO check state of instance
        // Get load generator id

        // DescribeInstanceStatusRequest lgWaitRequest = new DescribeInstanceStatusRequest().withInstanceIds(loadGeneratorInstanceId);

        // ec2.waiters().instanceStatusOk().run(new WaiterParameters().withRequest(lgWaitRequest));

        String loadGeneratorDNS = getInstancePublicDnsName(ec2, loadGeneratorInstanceId);

        // get web server id


        // DescribeInstanceStatusRequest wsWaitRequest = new DescribeInstanceStatusRequest().withInstanceIds(webServiceInstanceId);

        // ec2.waiters().instanceStatusOk().run(new WaiterParameters().withRequest(wsWaitRequest));

        String webServiceDNS = getInstancePublicDnsName(ec2, webServiceInstanceId);

        System.out.println(webServiceInstanceId);
        //  Tag instance using Tag Specification
        Tag tag = new Tag()
            .withKey("Project")
            .withValue("2.1");

        CreateTagsRequest lgTagRequest = new CreateTagsRequest()
            .withResources(loadGeneratorInstanceId)
            .withTags(tag);

        CreateTagsResult lgTagResponse = ec2.createTags(lgTagRequest);

        CreateTagsRequest wsTagRequest = new CreateTagsRequest()
            .withResources(webServiceInstanceId)
            .withTags(tag);

        CreateTagsResult wsTagResponse = ec2.createTags(wsTagRequest);

        System.out.println("Load Generator DNS is");
        System.out.println(loadGeneratorDNS);
        System.out.println("Web Server DNS is");
        System.out.println(webServiceDNS);
        //Setup TPZ Credentials
        authenticate(loadGeneratorDNS);
        System.out.println("Load Generator DNS authentication passed");
        //Initialize test
        String response = initializeTest(loadGeneratorDNS, webServiceDNS);
        System.out.println("Test Initialized");
        //Get TestID
        String testId = getTestId(response);
        System.out.println("test id is");
        System.out.println(testId);
        //Save launch time
        Date lastLaunchTime = new Date();

        //Monitor LOG file
        Ini ini = getIniUpdate(loadGeneratorDNS, testId);
        while (ini == null || !ini.containsKey("Test finished")) {
            // Everything belows is triggered when the test has not completed. "30 min has not been reached"
            Thread.sleep(1000);
            //  Check last launch time and RPS
            Date currentTime = new Date();
            long pastSeconds = (currentTime.getTime() - lastLaunchTime.getTime()) / 1000;
            if (pastSeconds > 100) { // wait until 100s has passed from last launch to start considering add a new instance
                float rps =  getRPS(ini);
                if(rps >= 50.0) {
                    break;
                } else {
                    //Everything belows is triggered after 1. The test has not completed; 
                                                         //2. 100 secs has passed after the completement of last test; 
                                                         //3. RPS still hasn't reached 50

                    // create and tag a new web service instance

                    webServiceResult = ec2.runInstances(runWebServiceRequest);

                    webServiceInstanceId = webServiceResult
                                                    .getReservation()
                                                    .getInstances()
                                                    .get(0)
                                                    .getInstanceId();

                    instanceIdArray.add(webServiceInstanceId);

                    // DescribeInstanceStatusRequest waitRequest = new DescribeInstanceStatusRequest().withInstanceIds(webServiceInstanceId);

                    // ec2.waiters().instanceStatusOk().run(new WaiterParameters().withRequest(waitRequest));
                    // Thread.sleep(120000);
                    Instance webServerInstance2 = getInstance(ec2, webServiceInstanceId);

                    while(webServerInstance2 == null || !webServerInstance2.getState().getName().equals("running")) {
                        Thread.sleep(800); // sleep < 1 sec
                        webServerInstance2 = getInstance(ec2, webServiceInstanceId);
                    }
                    webServiceDNS = getInstancePublicDnsName(ec2, webServiceInstanceId);

                    wsTagRequest = new CreateTagsRequest()
                        .withResources(webServiceInstanceId)
                        .withTags(tag);

                    wsTagResponse = ec2.createTags(wsTagRequest);
                    // end Create and tag a new web service instance

                    // add the new web service instance and start a new test
                    response = addWebServiceInstance(loadGeneratorDNS, webServiceDNS, testId);

                    //renew the launch time after starting a new test
                    lastLaunchTime = new Date();
                }
            }

            //  Add New Web Service Instance if Required
            ini = getIniUpdate(loadGeneratorDNS, testId);
        }
        // TODO Terminate all resources
        for (String id : instanceIdArray) {
            TerminateInstancesRequest terminateInstancesRequest = new TerminateInstancesRequest()
                .withInstanceIds(id);
            ec2.terminateInstances(terminateInstancesRequest);
            // TODO terminate instances
        }

        //TODO delete security groups

        DeleteSecurityGroupRequest request = new DeleteSecurityGroupRequest();

        request.withGroupName(WEB_SERVICE_SECURITY_GROUP);

        ec2.deleteSecurityGroup(request);

        DeleteSecurityGroupRequest request2 = new DeleteSecurityGroupRequest();

        request2.withGroupName(LG_SECURITY_GROUP);
        
        ec2.deleteSecurityGroup(request2);
    }

    /**
     * Get the latest RPS.
     * @param ini INI file object
     * @return RPS Value
     */
    private static float getRPS(final Ini ini) {
            float rps = 0;
            for (String key: ini.keySet()) {
                if (key.startsWith("Current rps")) {
                    rps = Float.parseFloat(key.split("=")[1]);
                }
            }
            return rps;
    }

    /**
     * Get the latest version of the log.
     * @param loadGeneratorDNS DNS Name of load generator
     * @param testId TestID String
     * @return INI Object
     * @throws IOException on network failure
     */
    private static Ini getIniUpdate(final String loadGeneratorDNS,
                                    final String testId)
            throws IOException {
        String response = HttpRequest.sendGet("http://"
                + loadGeneratorDNS + "/log?name=test." + testId + ".log");
        File log = new File(testId + ".log");
        FileUtils.writeStringToFile(log, response, Charset.defaultCharset());
        Ini ini = new Ini(log);
        return ini;
    }

    /**
     * Get ID of test.
     * @param response Response containing LoadGenerator output
     * @return TestID string
     */
    private static String getTestId(final String response) {
        Pattern pattern = Pattern.compile("test\\.([0-9]*)\\.log");
        Matcher matcher = pattern.matcher(response);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * Initializes Load Generator Test 
     * @param loadGeneratorDNS DNS Name of load generator
     * @param webServiceDNS DNS Name of web service
     * @return response of initialization (contains test ID)
     */
    private static String initializeTest(final String loadGeneratorDNS,
                                         final String webServiceDNS) {
        String response = "";
        boolean launchWebServiceSuccess = false;
        while (!launchWebServiceSuccess) {
            try {
                response = HttpRequest.sendGet("http://" + loadGeneratorDNS
                        + "/test/horizontal?dns=" + webServiceDNS);
                System.out.println(response);
                launchWebServiceSuccess = true;
            } catch (Exception e) {
                System.out.print("*");
            }
        }
        return response;
    }

    /**
     * Add a Web Service vm to Load Generator.
     * @param loadGeneratorDNS DNS Name of Load Generator
     * @param webServiceDNS DNS Name of Web Service
     * @return String response
     */
    private static String addWebServiceInstance(final String loadGeneratorDNS,
                                          final String webServiceDNS,
                                          final String testId) {
        String response = "";
        boolean launchWebServiceSuccess = false;
        while (!launchWebServiceSuccess) {
            try {
                response = HttpRequest.sendGet(
                        "http://" + loadGeneratorDNS
                                + "/test/horizontal/add?dns=" + webServiceDNS);
                System.out.println(response);
                launchWebServiceSuccess = true;
            } catch (Exception e) {
                try {
                    Thread.sleep(RETRY_DELAY_MILLIS);
                    Ini ini = getIniUpdate(loadGeneratorDNS, testId);
                    if (ini != null & ini.containsKey("Test finished")){
                        launchWebServiceSuccess = true;
                        System.out.println("New WS not submitted because test already completed");
                    }

                } catch (InterruptedException e1) {
                    e1.printStackTrace();
                } catch (Exception e2) { 
                    e2.printStackTrace();
                }
            }
        }
        return response;
    }

    /**
     * Supply LG with TPZ credentials.
     * @param loadGeneratorDNS DNS Name of load generator
     */
    private static void authenticate(final String loadGeneratorDNS) {
        boolean loadGeneratorSuccess = false;
        while (!loadGeneratorSuccess) {
            try {
                String response = HttpRequest.sendGet("http://"
                        + loadGeneratorDNS + "/password?passwd="
                        + SUBMISSION_PASSWORD
                        + "&username="
                        + TPZ_USERNAME);
                System.out.println(response);
                loadGeneratorSuccess = true;
            } catch (Exception e) {
                try {
                    Thread.sleep(RETRY_DELAY_MILLIS);
                } catch (InterruptedException e1) {
                    e1.printStackTrace();
                }
            }
        }
    }

    /**
     * Create a new HTTPSecurity Group.
     * @param ec2 EC2Client instance
     * @param securityGroupName Security group name
     */
    public static void createHttpSecurityGroup(final AmazonEC2 ec2,
                                               final String securityGroupName) {
        //TODO: Create Security Group
        CreateSecurityGroupRequest csgr = 
            new CreateSecurityGroupRequest();
        csgr.withGroupName(securityGroupName)
            .withDescription("My security group")
            .withVpcId(vpcId); // find vpcid
        CreateSecurityGroupResult createSecurityGroupResult = ec2.createSecurityGroup(csgr);
        
        //TODO: Add permission to security group
        IpPermission ipPermission = new IpPermission();
        IpRange ip_range = new IpRange().withCidrIp("0.0.0.0/0");

        ipPermission.withIpv4Ranges(ip_range)
            .withIpProtocol("tcp")
            .withFromPort(22)
            .withToPort(80);
        AuthorizeSecurityGroupIngressRequest authorizeSecurityGroupIngressRequest =
            new AuthorizeSecurityGroupIngressRequest()
                                    .withGroupName(securityGroupName)
                                    .withIpPermissions(ipPermission);

        AuthorizeSecurityGroupIngressResult authorizeSecurityGroupIngressResult =
                                ec2.authorizeSecurityGroupIngress(authorizeSecurityGroupIngressRequest);
    }

    /**
     * Get instance public DNS by instance ID.
     * @param ec2 EC2 client instance
     * @param instanceId instance ID
     * @return DNS name
     */
    public static String getInstancePublicDnsName(final AmazonEC2 ec2,
                                                  final  String instanceId) {
        //  Implement this method
        DescribeInstancesRequest request = new DescribeInstancesRequest();
        DescribeInstancesResult response = ec2.describeInstances(request);
        for(Reservation reservation : response.getReservations()) {
            for(Instance instance : reservation.getInstances()) {
                if (instance.getInstanceId().equals(instanceId)) {
                    return instance.getPublicDnsName();
                }
            }
        }
        return "";
    }

    /**
     * Get instance object by ID.
     * @param ec2 EC2 client instance
     * @param instanceId isntance ID
     * @return Instance Object
     */
    public static Instance getInstance(final AmazonEC2 ec2,
                                       final String instanceId){
        //  Implement this method
        DescribeInstancesRequest request = new DescribeInstancesRequest();
        DescribeInstancesResult response = ec2.describeInstances(request);
        for(Reservation reservation : response.getReservations()) {
            for(Instance instance : reservation.getInstances()) {
                if (instance.getInstanceId().equals(instanceId)) {
                    return instance;
                }
            }
        }
        return null;
    }
}
