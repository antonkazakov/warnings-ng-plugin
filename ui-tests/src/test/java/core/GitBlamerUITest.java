package core;

import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.jvnet.hudson.test.Issue;

import org.jenkinsci.test.acceptance.docker.DockerContainerHolder;
import org.jenkinsci.test.acceptance.docker.fixtures.GitContainer;
import org.jenkinsci.test.acceptance.junit.AbstractJUnitTest;
import org.jenkinsci.test.acceptance.junit.DockerTest;
import org.jenkinsci.test.acceptance.junit.WithCredentials;
import org.jenkinsci.test.acceptance.junit.WithDocker;
import org.jenkinsci.test.acceptance.junit.WithPlugins;
import org.jenkinsci.test.acceptance.plugins.git.GitScm;
import org.jenkinsci.test.acceptance.po.Build;
import org.jenkinsci.test.acceptance.po.FreeStyleJob;
import org.jenkinsci.test.acceptance.po.Job;
import org.jenkinsci.test.acceptance.po.WorkflowJob;

import io.jenkins.plugins.analysis.warnings.AnalysisResult;
import io.jenkins.plugins.analysis.warnings.AnalysisSummary;
import io.jenkins.plugins.analysis.warnings.BlamesTable;
import io.jenkins.plugins.analysis.warnings.BlamesTableRow;
import io.jenkins.plugins.analysis.warnings.IssuesRecorder;

import static org.assertj.core.api.Assertions.*;

@WithDocker
@Category(DockerTest.class)
@WithPlugins({"git", "git-forensics"})
@WithCredentials(credentialType = WithCredentials.SSH_USERNAME_PRIVATE_KEY, values = {"gitplugin", "/org/jenkinsci/test/acceptance/docker/fixtures/GitContainer/unsafe"})
public class GitBlamerUITest extends AbstractJUnitTest {

    @Inject
    DockerContainerHolder<GitContainer> gitServer;

    private static final String USERNAME = "gitplugin";
    private GitContainer container;
    private String repoUrl;
    private String host;
    private int port;

    private static final String DETAILS = "Details";
    private static final String FILE = "File";
    private static final String AGE = "Age";
    private static final String AUTHOR = "Author";
    private static final String EMAIL = "Email";
    private static final String COMMIT = "Commit";
    private static final String ADDED = "Added";

    @Before
    public void initGitRepository() {
        container = gitServer.get();
        repoUrl = container.getRepoUrl();
        host = container.host();
        port = container.port();
    }

    @Test
    public void shouldBlameOneIssueWithFreestyle() {
        GitRepo repo = setupInitialGitRepository();
        repo.commitFileWithMessage("commit", "Test.java",
                "public class Test {}");
        String commitId = repo.getLastSha1();
        repo.commitFileWithMessage("commit", "warnings.txt",
                "[javac] Test.java:1: warning: Test Warning for Jenkins");

        Build build = generateFreeStyleJob(repo);
        build.open();

        AnalysisSummary blame = new AnalysisSummary(build, "java");
        AnalysisResult resultPage = blame.openOverallResult();
        BlamesTable blamesTable = resultPage.openBlamesTable();
        BlamesTableRow row = blamesTable.getRowAs(0, BlamesTableRow.class);

        assertThat(blamesTable.getTableRows()).hasSize(1);
        assertColumnHeader(blamesTable);
        assertColumnsOfTest(row, commitId);
    }

    @Test
    public void shouldBlameElevenIssuesWithPipeline() throws Exception {
        GitRepo repo = new GitRepo();
        Map<String, String> commits = commitDifferentFilesToGitRepository(repo);
        repo.commitFileWithMessage("commit", "Jenkinsfile",
                "node {\n"
                        + "  stage ('Checkout') {\n"
                        + "    checkout scm\n"
                        + "  }\n"
                        + "  stage ('Build and Analysis') {"
                        + "    echo '[javac] Test.java:1: warning: Test Warning for Jenkins'\n"
                        + "    echo '[javac] Test.java:2: warning: Test Warning for Jenkins'\n"
                        + "    echo '[javac] Test.java:3: warning: Test Warning for Jenkins'\n"
                        + "    echo '[javac] LoremIpsum.java:1: warning: Another Warning for Jenkins'\n"
                        + "    echo '[javac] LoremIpsum.java:2: warning: Another Warning for Jenkins'\n"
                        + "    echo '[javac] LoremIpsum.java:3: warning: Another Warning for Jenkins'\n"
                        + "    echo '[javac] LoremIpsum.java:4: warning: Another Warning for Jenkins'\n"
                        + "    echo '[javac] Bob.java:1: warning: Bobs Warning for Jenkins'\n"
                        + "    echo '[javac] Bob.java:2: warning: Bobs Warning for Jenkins'\n"
                        + "    echo '[javac] Bob.java:3: warning: Bobs Warning for Jenkins'\n"
                        + "    recordIssues tools: [java()]\n"
                        + "  }\n"
                        + "}"
        );

        Build build = generateWorkflowJob(repo);
        build.open();

        AnalysisSummary blame = new AnalysisSummary(build, "java");
        AnalysisResult resultPage = blame.openOverallResult();
        BlamesTable blamesTable = resultPage.openBlamesTable();

        assertThat(blamesTable.getTableRows()).hasSize(10);
        assertColumnHeader(blamesTable);
        assertElevenIssues(commits, blamesTable);
    }

    @Test
    public void shouldBlameElevenIssuesWithFreestyle() throws Exception {
        GitRepo repo = new GitRepo();
        Map<String, String> commits = commitDifferentFilesToGitRepository(repo);
        repo.commitFileWithMessage("commit", "warnings.txt",
                "[javac] Test.java:1: warning: Test Warning for Jenkins\n"
                        + "[javac] Test.java:2: warning: Test Warning for Jenkins\n"
                        + "[javac] Test.java:3: warning: Test Warning for Jenkins\n"
                        + "[javac] LoremIpsum.java:1: warning: Another Warning for Jenkins\n"
                        + "[javac] LoremIpsum.java:2: warning: Another Warning for Jenkins\n"
                        + "[javac] LoremIpsum.java:3: warning: Another Warning for Jenkins\n"
                        + "[javac] LoremIpsum.java:4: warning: Another Warning for Jenkins\n"
                        + "[javac] Bob.java:1: warning: Bobs Warning for Jenkins\n"
                        + "[javac] Bob.java:2: warning: Bobs Warning for Jenkins\n"
                        + "[javac] Bob.java:3: warning: Bobs Warning for Jenkins");

        Build build = generateFreeStyleJob(repo);
        build.open();

        AnalysisSummary blame = new AnalysisSummary(build, "java");
        AnalysisResult resultPage = blame.openOverallResult();
        BlamesTable blamesTable = resultPage.openBlamesTable();

        assertThat(blamesTable.getTableRows()).hasSize(10);
        assertColumnHeader(blamesTable);
        assertElevenIssues(commits, blamesTable);
    }

    /**
     * Test if blaming works on a build out of tree. See JENKINS-57260.
     *
     * @throws Exception
     *         if there is a problem with the git repository
     */
    @Test
    @Issue("JENKINS-57260")
    public void shouldBlameWithBuildOutOfTree() throws Exception {
        GitRepo repo = setupInitialGitRepository();
        repo.commitFileWithMessage("commit", "Test.h", "#ifdef \"");

        String firstCommit = repo.getLastSha1();

        repo.commitFileWithMessage("commit", "Jenkinsfile", "pipeline {\n"
                + "  agent any\n"
                + "  options {\n"
                + "    skipDefaultCheckout()\n"
                + "  }\n"
                + "  stages {\n"
                + "    stage('Prepare') {\n"
                + "      steps {\n"
                + "        dir('source') {\n"
                + "          checkout scm\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "    stage('Doxygen') {\n"
                + "      steps {\n"
                + "        dir('build/doxygen') {\n"
                + "          echo 'Test.h:1: Error: Unexpected character'\n"
                + "        }\n"
                + "        recordIssues(aggregatingResults: true, "
                + "             enabledForFailure: true, "
                + "             tool: doxygen(name: 'Doxygen'), "
                + "             sourceDirectory: 'source'"
                + "        )\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "}");

        Build build = generateWorkflowJob(repo);
        build.open();

        AnalysisSummary blame = new AnalysisSummary(build, "doxygen");
        AnalysisResult resultPage = blame.openOverallResult();
        BlamesTable blamesTable = resultPage.openBlamesTable();

        assertColumnHeader(blamesTable);
        assertThat(blamesTable.getTableRows()).hasSize(1);
        BlamesTableRow row = blamesTable.getRowAs(0, BlamesTableRow.class);

        assertThat(row.getAuthor()).isEqualTo("Git SampleRepoRule");
        assertThat(row.getEmail()).isEqualTo("gits@mplereporule");
        assertThat(row.getFileName()).isEqualTo("Test.h");
        assertThat(row.getCommit()).isEqualTo(firstCommit);
    }

    private void assertElevenIssues(final Map<String, String> commits, final BlamesTable table) {
        assertColumnsOfRowBob(table.getRowAs(0, BlamesTableRow.class), commits.get("Bob"));
        assertColumnsOfRowBob(table.getRowAs(1, BlamesTableRow.class), commits.get("Bob"));
        assertColumnsOfRowBob(table.getRowAs(2, BlamesTableRow.class), commits.get("Bob"));

        assertColumnsOfRowLoremIpsum(table.getRowAs(3, BlamesTableRow.class), commits.get("LoremIpsum"));
        assertColumnsOfRowLoremIpsum(table.getRowAs(4, BlamesTableRow.class), commits.get("LoremIpsum"));
        assertColumnsOfRowLoremIpsum(table.getRowAs(5, BlamesTableRow.class), commits.get("LoremIpsum"));
        assertColumnsOfRowLoremIpsum(table.getRowAs(6, BlamesTableRow.class), commits.get("LoremIpsum"));

        assertColumnsOfTest(table.getRowAs(7, BlamesTableRow.class), commits.get("Test"));
        assertColumnsOfTest(table.getRowAs(8, BlamesTableRow.class), commits.get("Test"));
        assertColumnsOfTest(table.getRowAs(9, BlamesTableRow.class), commits.get("Test"));
    }

    private void assertColumnsOfTest(final BlamesTableRow row, final String commit) {
        assertThat(row.getAuthor()).isEqualTo("Git SampleRepoRule");
        assertThat(row.getEmail()).isEqualTo("gits@mplereporule");
        assertThat(row.getFileName()).isEqualTo("Test.java");
        assertThat(row.getCommit()).isEqualTo(commit);
        assertThat(row.getAge()).isEqualTo(1);
    }

    private void assertColumnsOfRowBob(final BlamesTableRow row, final String commit) {
        assertThat(row.getAuthor()).isEqualTo("Alice Miller");
        assertThat(row.getEmail()).isEqualTo("alice@miller");
        assertThat(row.getFileName()).isEqualTo("Bob.java");
        assertThat(row.getCommit()).isEqualTo(commit);
        assertThat(row.getAge()).isEqualTo(1);
    }

    private void assertColumnsOfRowLoremIpsum(final BlamesTableRow row, final String commit) {
        assertThat(row.getAuthor()).isEqualTo("John Doe");
        assertThat(row.getEmail()).isEqualTo("john@doe");
        assertThat(row.getFileName()).isEqualTo("LoremIpsum.java");
        assertThat(row.getCommit()).isEqualTo(commit);
        assertThat(row.getAge()).isEqualTo(1);
    }

    private void assertColumnHeader(final BlamesTable table) {
        assertThat(table.getHeaders()).containsExactly(DETAILS, FILE, AGE, AUTHOR, EMAIL, COMMIT, ADDED);
    }

    private Build generateFreeStyleJob(final GitRepo repo) {
        FreeStyleJob freestyleJob = jenkins.jobs.create();
        freestyleJob.configure();
        repo.transferToDockerContainer(host, port);
        freestyleJob.useScm(GitScm.class)
                .url(repoUrl)
                .credentials(USERNAME);

        addRecorder(freestyleJob);
        freestyleJob.save();

        return freestyleJob.startBuild().waitUntilFinished();
    }

    private Build generateWorkflowJob(final GitRepo repo) {
        WorkflowJob workflowJob = jenkins.jobs.create(WorkflowJob.class);
        workflowJob.configure();
        repo.transferToDockerContainer(host, port);
        workflowJob.setJenkinsFileRepository(repoUrl, USERNAME);
        workflowJob.save();
        return workflowJob.startBuild().waitUntilFinished();
    }

    private void addRecorder(final FreeStyleJob job) {
        job.addPublisher(IssuesRecorder.class, recorder -> {
            recorder.setTool("Java").setPattern("warnings.txt");
            recorder.setEnabledForFailure(true);
        });
    }

    private GitRepo setupInitialGitRepository() {
        GitRepo repo = new GitRepo();
        repo.setIdentity("Git SampleRepoRule", "gits@mplereporule");
        repo.commitFileWithMessage("init", "file", "");
        return repo;
    }

    private Map<String, String> commitDifferentFilesToGitRepository(GitRepo repo) {
        Map<String, String> commits = new HashMap<>();

        repo.setIdentity("Git SampleRepoRule", "gits@mplereporule");
        repo.commitFileWithMessage("commit", "Test.java", "public class Test {\n"
                + "    public Test() {\n"
                + "        System.out.println(\"Test\");"
                + "    }\n"
                + "}");
        commits.put("Test", repo.getLastSha1());

        repo.setIdentity("John Doe", "john@doe");
        repo.commitFileWithMessage("commit", "LoremIpsum.java", "public class LoremIpsum {\n"
                + "    public LoremIpsum() {\n"
                + "        Log.log(\"Lorem ipsum dolor sit amet\");"
                + "    }\n"
                + "}");
        commits.put("LoremIpsum", repo.getLastSha1());

        repo.setIdentity("Alice Miller", "alice@miller");
        repo.commitFileWithMessage("commit", "Bob.java", "public class Bob {\n"
                + "    public Bob() {\n"
                + "        Log.log(\"Bob: 'Where are you?'\");"
                + "    }\n"
                + "}");
        commits.put("Bob", repo.getLastSha1());

        return commits;
    }

}
