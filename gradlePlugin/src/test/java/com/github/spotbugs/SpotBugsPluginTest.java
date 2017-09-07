package com.github.spotbugs;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.BuildTask;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

import com.google.common.io.Files;

@RunWith(Theories.class)
public class SpotBugsPluginTest extends Assert{
  @Rule
  public TemporaryFolder folder= new TemporaryFolder();

  /**
   * Supported gradle versions
   */
  @DataPoints
  public static String[] gradleVersions = new String[] { "4.0", "4.1" };

  @Before
  public void createProject() throws IOException {
    String buildScript = "plugins {\n" +
      "  id 'java'\n" +
      "  id 'com.github.spotbugs'\n" +
      "}\n" +
      "version = 1.0\n" +
      "repositories {\n" +
      "  mavenCentral()\n" +
      "  mavenLocal()\n" +
      "}";
    File buildFile = folder.newFile("build.gradle");
    Files.write(buildScript.getBytes(StandardCharsets.UTF_8), buildFile);

    File sourceDir = folder.newFolder("src", "main", "java");
    File to = new File(sourceDir, "Foo.java");
    File from = new File("src/test/java/com/github/spotbugs/Foo.java");
    Files.copy(from, to);
  }

  @Test
  public void TestSpotBugsTasksExist() throws Exception{
    BuildResult result = GradleRunner.create().withProjectDir(folder.getRoot()).withArguments(Arrays.asList("tasks", "--all")).withPluginClasspath().build();
    assertTrue(result.getOutput().contains("spotbugsMain"));
    assertTrue(result.getOutput().contains("spotbugsTest"));
  }

  @Theory
  public void testSpotBugsTaskCanRun(String gradleVersion) throws Exception {
    BuildResult result = GradleRunner.create()
            .withGradleVersion(gradleVersion)
            .withProjectDir(folder.getRoot())
            .withArguments(Arrays.asList("compileJava", "spotbugsMain"))
            .withPluginClasspath().build();
    Optional<BuildTask> spotbugsMain = findTask(result, ":spotbugsMain");
    assertTrue(spotbugsMain.isPresent());
    assertThat(spotbugsMain.get().getOutcome(), is(TaskOutcome.SUCCESS));
  }

  @Theory
  public void testSpotBugsTestTaskCanRun(String gradleVersion) throws Exception {
    BuildResult result = GradleRunner.create()
            .withGradleVersion(gradleVersion)
            .withProjectDir(folder.getRoot())
            .withArguments(Arrays.asList("compileTestJava", "spotbugsTest"))
            .withPluginClasspath().build();
    Optional<BuildTask> spotbugsTest = findTask(result, ":spotbugsTest");
    assertTrue(spotbugsTest.isPresent());
    assertThat(spotbugsTest.get().getOutcome(), is(TaskOutcome.NO_SOURCE));
  }

  @Theory
  public void testCheckTaskDependsOnSpotBugsTasks(String gradleVersion) throws Exception {
    BuildResult result = GradleRunner.create()
            .withGradleVersion(gradleVersion)
            .withProjectDir(folder.getRoot())
            .withArguments(Arrays.asList("compileJava", "compileTestJava", "check"))
            .withPluginClasspath().build();
    assertTrue(findTask(result, ":spotbugsMain").isPresent());
    assertTrue(findTask(result, ":spotbugsTest").isPresent());
  }

  private Optional<BuildTask> findTask(BuildResult result, String taskName) {
    return result.getTasks().stream()
          .filter(task -> task.getPath().equals(taskName))
          .findAny();
  }

  @Test
  public void testLoadToolVersion() {
    SpotBugsPlugin plugin = new SpotBugsPlugin();
    assertThat(plugin.loadToolVersion(), is(notNullValue()));
  }
}
