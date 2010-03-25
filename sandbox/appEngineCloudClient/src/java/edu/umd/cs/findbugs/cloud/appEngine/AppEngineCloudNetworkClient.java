package edu.umd.cs.findbugs.cloud.appEngine;

import com.google.protobuf.GeneratedMessage;
import edu.umd.cs.findbugs.BugDesignation;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.IGuiCallback;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.cloud.NotSignedInException;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.AppEngineProtoUtil;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.Evaluation;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.FindIssues;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.FindIssuesResponse;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.GetRecentEvaluations;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.Issue;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.LogIn;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.RecentEvaluations;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.SetBugLink;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.UploadEvaluation;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.UploadIssues;
import edu.umd.cs.findbugs.cloud.appEngine.protobuf.ProtoClasses.UploadIssues.Builder;
import edu.umd.cs.findbugs.cloud.username.AppEngineNameLookup;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import static edu.umd.cs.findbugs.cloud.appEngine.protobuf.AppEngineProtoUtil.encodeHash;

public class AppEngineCloudNetworkClient {
    private static final Logger LOGGER = Logger.getLogger(AppEngineCloudNetworkClient.class.getName());
    /** For debugging */
    private static final boolean FORCE_UPLOAD_ALL_ISSUES = false;
    private static final int BUG_UPLOAD_PARTITION_SIZE = 10;
    /** Big enough to keep total find-issues time down AND also keep individual request time down (for Google's sake) */
    private static final int HASH_CHECK_PARTITION_SIZE = 60;

    private AppEngineCloudClient cloudClient;
    private AppEngineNameLookup lookerupper;
    private ConcurrentMap<String, Issue> issuesByHash = new ConcurrentHashMap<String, Issue>();
    private String host;
    private Long sessionId;
    private String username;
    private volatile long mostRecentEvaluationMillis = 0;

    public void setCloudClient(AppEngineCloudClient appEngineCloudClient) {
        this.cloudClient = appEngineCloudClient;
    }

    /** returns whether soft initialization worked and the user is now signed in */
    public boolean initialize() throws IOException {
        lookerupper = new AppEngineNameLookup();
        lookerupper.initializeSoftly(cloudClient.getPlugin());
        this.sessionId = lookerupper.getSessionId();
        this.username = lookerupper.getUsername();
        this.host = lookerupper.getHost();
        return this.sessionId != null;
    }

    public void signIn(boolean force) throws IOException {
        if (!force && sessionId != null)
            throw new IllegalStateException("already signed in");
        if (!lookerupper.initialize(cloudClient.getPlugin(), cloudClient.getBugCollection())) {
            getGuiCallback().setErrorMessage("Signing into FindBugs Cloud failed!");
            return;
        }
        this.sessionId = lookerupper.getSessionId();
        this.username = lookerupper.getUsername();
        this.host = lookerupper.getHost();
        if (getUsername() == null || host == null) {
            throw new IllegalStateException("No App Engine Cloud username or hostname found! Check etc/findbugs.xml");
        }
        // now that we know our own username, we need to update all the bugs in the UI to show what "our"
        // designation & comments are.
        // this might be really slow with a lot of issues. seems fine so far.
        for (BugInstance instance : cloudClient.getBugCollection().getCollection()) {
            Issue issue = issuesByHash.get(instance.getInstanceHash());
            if (issue != null && issue.getEvaluationsCount() > 0) {
                cloudClient.updateBugInstanceAndNotify(instance);
            }
        }
    }

    public void setBugLinkOnCloud(BugInstance b, ProtoClasses.BugLinkType type, String bugLink) throws IOException, NotSignedInException {
        cloudClient.signInIfNecessary("To store the bug URL on the FindBugs cloud, you must sign in.");

        HttpURLConnection conn = openConnection("/set-bug-link");
        conn.setDoOutput(true);
        try {
            OutputStream outputStream = conn.getOutputStream();
            SetBugLink.newBuilder()
                    .setSessionId(sessionId)
                    .setHash(encodeHash(b.getInstanceHash()))
                    .setBugLinkType(type)
                    .setUrl(bugLink)
                    .build()
                    .writeTo(outputStream);
            outputStream.close();
            if (conn.getResponseCode() != 200) {
                throw new IllegalStateException(
                        "server returned error code "
                        + conn.getResponseCode() + " "
                        + conn.getResponseMessage());
            }
        } finally {
            conn.disconnect();
        }
    }

    public void setBugLinkOnCloudAndStoreIssueDetails(BugInstance b, String viewUrl, ProtoClasses.BugLinkType linkType)
            throws IOException, NotSignedInException {
        setBugLinkOnCloud(b, linkType, viewUrl);

        String hash = b.getInstanceHash();
        storeIssueDetails(hash,
                          Issue.newBuilder(getIssueByHash(hash))
                                  .setBugLink(viewUrl)
                                  .setBugLinkType(linkType)
                                  .build());
    }

    public void logIntoCloudForce() throws IOException {
        HttpURLConnection conn = openConnection("/log-in");
        conn.setDoOutput(true);
        conn.connect();

        OutputStream stream = conn.getOutputStream();
        LogIn logIn = LogIn.newBuilder()
                .setSessionId(sessionId)
                .setAnalysisTimestamp(cloudClient.getBugCollection().getAnalysisTimestamp())
                .build();
        logIn.writeTo(stream);
        stream.close();

        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new IllegalStateException("Could not log into cloud with ID " + sessionId + " - error "
                                            + responseCode + " " + conn.getResponseMessage());
        }
    }

    public void partitionHashes(final List<String> hashes,
                                List<Callable<Object>> tasks,
                                final ConcurrentMap<String, BugInstance> bugsByHash)
            throws IOException {
        final int numBugs = hashes.size();
        final AtomicInteger numberOfBugsCheckedSoFar = new AtomicInteger();
        for (int i = 0; i < numBugs; i += HASH_CHECK_PARTITION_SIZE) {
            final List<String> partition = hashes.subList(i, Math.min(i + HASH_CHECK_PARTITION_SIZE, numBugs));
            tasks.add(new Callable<Object>() {
                public Object call() throws Exception {
                    checkHashesPartition(partition, bugsByHash);
                    numberOfBugsCheckedSoFar.addAndGet(partition.size());
                    cloudClient.setStatusMsg("Checking " + numBugs + " bugs against the FindBugs Cloud..."
                                             + (numberOfBugsCheckedSoFar.get() * 100 / numBugs) + "%");
                    return null;
                }
            });
        }
    }

    private void checkHashesPartition(List<String> hashes, Map<String, BugInstance> bugsByHash) throws IOException {
        FindIssuesResponse response = submitHashes(hashes);

        for (int j = 0; j < hashes.size(); j++) {
            String hash = hashes.get(j);
            Issue issue = response.getFoundIssues(j);

            if (isEmpty(issue))
                // the issue was not found!
                continue;

            storeIssueDetails(hash, issue);

            BugInstance bugInstance;
            if (FORCE_UPLOAD_ALL_ISSUES) bugInstance = bugsByHash.get(hash);
            else bugInstance = bugsByHash.remove(hash);

            if (bugInstance == null) {
                LOGGER.warning("Server sent back issue that we don't know about: " + hash + " - " + issue);
                continue;
            }

            cloudClient.updateBugInstanceAndNotify(bugInstance);
        }
    }

    private boolean isEmpty(Issue issue) {
        return !issue.hasFirstSeen() && !issue.hasLastSeen() && issue.getEvaluationsCount() == 0;
    }

    public void uploadNewBugs(final List<BugInstance> newBugs, List<Callable<Object>> callables) throws NotSignedInException {
        cloudClient.signInIfNecessary("Some bugs were not found on the FindBugs Cloud service.\n" +
                                      "Would you like to sign in and upload them to the Cloud?");
        final AtomicInteger bugsUploaded = new AtomicInteger(0);
        final int bugCount = newBugs.size();
        for (int i = 0; i < bugCount; i += BUG_UPLOAD_PARTITION_SIZE) {
            final List<BugInstance> partition = newBugs.subList(i, Math.min(bugCount, i + BUG_UPLOAD_PARTITION_SIZE));
            callables.add(new Callable<Object>() {
                public Object call() throws Exception {
                    uploadNewBugsPartition(partition);
                    bugsUploaded.addAndGet(partition.size());
                    cloudClient.setStatusMsg("Uploading " + bugCount
                                             + " new bugs to the FindBugs Cloud..." + (bugsUploaded.get() * 100 / bugCount) + "%");
                    return null;
                }
            });
        }
    }

    public long getFirstSeenFromCloud(BugInstance b) {
        Issue issue = issuesByHash.get(b.getInstanceHash());
        if (issue == null || issue.getFirstSeen() == 0)
            return Long.MAX_VALUE;
        return issue.getFirstSeen();
    }

    public void storeIssueDetails(String hash, Issue issue) {
        for (Evaluation eval : issue.getEvaluationsList()) {
            if (eval.getWhen() > mostRecentEvaluationMillis) {
                mostRecentEvaluationMillis = eval.getWhen();
            }
        }
        issuesByHash.put(hash, issue);
    }

    private IGuiCallback getGuiCallback() {
        return cloudClient.getBugCollection().getProject().getGuiCallback();
    }

    private FindIssuesResponse submitHashes(List<String> bugsByHash)
            throws IOException {
        LOGGER.info("Checking " + bugsByHash.size() + " bugs against App Engine Cloud");
        FindIssues.Builder msgb = FindIssues.newBuilder();
        if (sessionId != null) {
            msgb.setSessionId(sessionId);
        }
        FindIssues hashList = msgb.addAllMyIssueHashes(AppEngineProtoUtil.encodeHashes(bugsByHash))
                .build();

        long start = System.currentTimeMillis();
        HttpURLConnection conn = openConnection("/find-issues");
        conn.setDoOutput(true);
        conn.connect();
        LOGGER.info("Connected in " + (System.currentTimeMillis() - start) + "ms");

        start = System.currentTimeMillis();
        OutputStream stream = conn.getOutputStream();
        hashList.writeTo(stream);
        stream.close();
        long elapsed = System.currentTimeMillis() - start;
        LOGGER.info("Submitted hashes (" + hashList.getSerializedSize() / 1024 + " KB) in " + elapsed + "ms ("
                                         + (elapsed / bugsByHash.size()) + "ms per hash)");

        start = System.currentTimeMillis();
        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            LOGGER.info("Error " + responseCode + ", took " + (System.currentTimeMillis() - start) + "ms");
            throw new IOException("Response code " + responseCode + " : " + conn.getResponseMessage());
        }
        FindIssuesResponse response = FindIssuesResponse.parseFrom(conn.getInputStream());
        conn.disconnect();
        int foundIssues = response.getFoundIssuesCount();
        elapsed = System.currentTimeMillis() - start;
        LOGGER.info("Received " + foundIssues + " bugs from server in " + elapsed + "ms ("
                                         + (elapsed / (foundIssues + 1)) + "ms per bug)");
        return response;
    }

    private void uploadNewBugsPartition(final Collection<BugInstance> bugsToSend)
            throws IOException {

        LOGGER.info("Uploading " + bugsToSend.size() + " bugs to App Engine Cloud");
        UploadIssues uploadIssues = buildUploadIssuesCommandInUIThread(bugsToSend);
        if (uploadIssues == null)
            return;
        openPostUrl(uploadIssues, "/upload-issues");

        // if it worked, store the issues locally
        final List<String> hashes = new ArrayList<String>();
        for (final Issue issue : uploadIssues.getNewIssuesList()) {
            final String hash = AppEngineProtoUtil.decodeHash(issue.getHash());
            storeIssueDetails(hash, issue);
            hashes.add(hash);
        }
        
        // let the GUI know that things changed
        cloudClient.getBugUpdateExecutor().execute(new Runnable() {
            public void run() {
                for (String hash : hashes) {
                    BugInstance bugInstance = cloudClient.getBugByHash(hash);
                    if (bugInstance != null) {
                        cloudClient.updatedIssue(bugInstance);
                    }
                }
            }
        });
    }

    private UploadIssues buildUploadIssuesCommandInUIThread(final Collection<BugInstance> bugsToSend) {
        ExecutorService updateExecutor = getGuiCallback().getBugUpdateExecutor();

        Future<UploadIssues> future = updateExecutor.submit(new Callable<UploadIssues>() {
            public UploadIssues call() throws Exception {
                Builder issueList = UploadIssues.newBuilder();
                issueList.setSessionId(sessionId);
                for (BugInstance bug : bugsToSend) {
                    issueList.addNewIssues(Issue.newBuilder()
                            .setHash(encodeHash(bug.getInstanceHash()))
                            .setBugPattern(bug.getType())
                            .setPriority(bug.getPriority())
                            .setPrimaryClass(bug.getPrimaryClass().getClassName())
                            .setFirstSeen(cloudClient.getFirstSeen(bug))
                            .build());
                }
                return issueList.build();
            }
        });
        try {
            return future.get();
        } catch (InterruptedException e) {
            LOGGER.log(Level.SEVERE, "", e);
            return null;
        } catch (ExecutionException e) {
            LOGGER.log(Level.SEVERE, "", e);
            return null;
        }
    }

    /**
     * package-private for testing
     */
    HttpURLConnection openConnection(String url) throws IOException {
        URL u = new URL(host + url);
        return (HttpURLConnection) u.openConnection();
    }

    public RecentEvaluations getRecentEvaluationsFromServer() throws IOException {
        HttpURLConnection conn = openConnection("/get-recent-evaluations");
        conn.setDoOutput(true);
        try {
            OutputStream outputStream = conn.getOutputStream();
            GetRecentEvaluations.Builder msgb = GetRecentEvaluations.newBuilder();
            if (sessionId != null) {
                msgb.setSessionId(sessionId);
            }
            msgb.setTimestamp(mostRecentEvaluationMillis);

            msgb.build().writeTo(outputStream);
            outputStream.close();

            if (conn.getResponseCode() != 200) {
                throw new IllegalStateException(
                        "server returned error code "
                        + conn.getResponseCode() + " "
                        + conn.getResponseMessage());
            }
            return RecentEvaluations.parseFrom(conn.getInputStream());
        } finally {
            conn.disconnect();
        }
    }

    public Evaluation getMostRecentEvaluation(BugInstance b) {
        Issue issue = issuesByHash.get(b.getInstanceHash());
        if (issue == null)
            return null;
        Evaluation mostRecent = null;
        long when = Long.MIN_VALUE;
        for (Evaluation e : issue.getEvaluationsList())
            if (e.getWho().equals(cloudClient.getUser()) && e.getWhen() > when) {
                mostRecent = e;
                when = e.getWhen();
            }

        return mostRecent;
    }

    private void openPostUrl(GeneratedMessage uploadMsg, String url) {
        try {
            HttpURLConnection conn = openConnection(url);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");
            conn.connect();
            try {
                OutputStream stream = conn.getOutputStream();
                if (uploadMsg != null) {
                    uploadMsg.writeTo(stream);
                } else {
                    stream.write(0);
                }
                stream.close();
                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    throw new IllegalStateException(
                            "server returned error code when opening " + url + ": "
                            + conn.getResponseCode() + " "
                            + conn.getResponseMessage());
                }
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String getUsername() {
        return username;
    }

    @SuppressWarnings({"deprecation"})
    public void storeUserAnnotation(BugInstance bugInstance) throws NotSignedInException {
        // store this stuff first because signIn might clobber it. this is kludgy but works.
        BugDesignation designation = bugInstance.getNonnullUserDesignation();
        long timestamp = designation.getTimestamp();
        String designationKey = designation.getDesignationKey();
        String comment = designation.getAnnotationText();

        cloudClient.signInIfNecessary("To store your evaluation on the FindBugs Cloud, you must sign in first.");
        
        Evaluation.Builder evalBuilder = Evaluation.newBuilder()
                .setWhen(timestamp)
                .setDesignation(designationKey);
        if (comment != null) {
            evalBuilder.setComment(comment);
        }
        UploadEvaluation uploadMsg = UploadEvaluation.newBuilder()
                .setSessionId(sessionId)
                .setHash(encodeHash(bugInstance.getInstanceHash()))
                .setEvaluation(evalBuilder.build())
                .build();

        openPostUrl(uploadMsg, "/upload-evaluation");
    }

    public @CheckForNull Issue getIssueByHash(String hash) {
        return issuesByHash.get(hash);
    }

    /** for testing */
    void setUsername(String username) {
        this.username = username;
    }

    /** for testing */
    void setSessionId(long sessionId) {
        this.sessionId = sessionId;
    }

    public void signOut() {
        if (sessionId != null) {
            openPostUrl(null, "/log-out/" + sessionId);
            sessionId = null;
            AppEngineNameLookup.clearSavedSessionInformation();
        }
    }

    public Long getSessionId() {
        return sessionId;
    }
}