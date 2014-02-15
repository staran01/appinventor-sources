// -*- mode: java; c-basic-offset: 2; -*-
// Copyright 2009-2011 Google, All Rights reserved
// Copyright 2011-2012 MIT, All rights reserved
// Released under the MIT License https://raw.github.com/mit-cml/app-inventor/master/mitlicense.txt
package com.google.appinventor.buildserver;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.text.DateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.StringArrayOptionHandler;

import com.google.appinventor.common.version.GitBuildId;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.sun.grizzly.http.SelectorThread;
import com.sun.jersey.api.container.grizzly.GrizzlyServerFactory;

/**
 * Top level class for exposing the building of App Inventor APK files as a RESTful web service.
 *
 * Note that these BuildServer objects are created per request
 *
 * @author markf@google.com (Mark Friedman)
 */
// The Java class will be hosted at the URI path "/buildserver"
@Path("/buildserver")
public class BuildServer {
  private ProjectBuilder projectBuilder = new ProjectBuilder();

  static class CommandLineOptions {
    @Option(name = "--childProcessRamMb",
      usage = "Maximum ram that can be used by a child processes, in MB.")
    int childProcessRamMb = 2048;

    @Option(name = "--maxSimultaneousBuilds",
      usage = "Maximum number of builds that can run in parallel. O means unlimited.")
    int maxSimultaneousBuilds = 0;  // The default is unlimited.

    @Option(name = "--port",
      usage = "The port number to bind to on the local machine.")
    int port = 9990;

    @Option(name = "--requiredHosts",
      usage = "If specified, a list of hosts which are permitted to use this BuildServer, other the server is open to all.",
      handler = StringArrayOptionHandler.class)
    String[] requiredHosts = null;

    @Option(name = "--debug",
      usage = "Turn on debugging, which enables the non-async calls of the buildserver.")
    boolean debug = false;
    @Option(name = "--dexCacheDir",
            usage = "the directory to cache the pre-dexed libraries")
    String dexCacheDir = null;

  }

  private static final CommandLineOptions commandLineOptions = new CommandLineOptions();

  // Logging support
  private static final Logger LOG = Logger.getLogger(BuildServer.class.getName());

  private static final MediaType APK_MEDIA_TYPE =
    new MediaType("application", "vnd.android.package-archive",
      ImmutableMap.of("charset", "utf-8"));

  private static final MediaType ZIP_MEDIA_TYPE =
    new MediaType("application", "zip", ImmutableMap.of("charset", "utf-8"));

  private static final AtomicInteger buildCount = new AtomicInteger(0);

  // The number of build requests for this server run
  private static final AtomicInteger asyncBuildRequests = new AtomicInteger(0);

  // The number of rejected build requests for this server run
  private static final AtomicInteger rejectedAsyncBuildRequests = new AtomicInteger(0);

  //The number of successful build requests for this server run
  private static final AtomicInteger successfulBuildRequests = new AtomicInteger(0);

  //The number of failed build requests for this server run
  private static final AtomicInteger failedBuildRequests = new AtomicInteger(0);

  //The number of failed build requests for this server run
  private static int maximumActiveBuildTasks = 0;

  // The build executor used to limit the number of simultaneous builds.
  // NOTE(lizlooney) - the buildExecutor must be created after the command line options are
  // processed in main(). If it is created here, the number of simultaneous builds will always be
  // the default value, even if the --maxSimultaneousBuilds option is on the command line.
  private static NonQueuingExecutor buildExecutor;

  // The input zip file. It will be deleted in cleanUp.
  private File inputZip;

  // The built APK file for this build request, if any.
  private File outputApk;

  // The temp directory that we're building in.
  private File outputDir;

  // The android.keystore file generated by this build request, if necessary.
  private File outputKeystore;

  // The zip file where we put all the build results for this request.
  private File outputZip;

  @GET
  @Path("health")
  @Produces(MediaType.TEXT_PLAIN)
  public Response health() throws IOException {
    return Response.ok("ok", MediaType.TEXT_PLAIN_TYPE).build();
  }

  @GET
  @Path("vars")
  @Produces(MediaType.TEXT_HTML)
  public Response var() throws IOException {
    Map<String, String> variables = new LinkedHashMap<String, String>();

    // Runtime
    RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
    DateFormat dateTimeFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.FULL);
    variables.put("start-time", dateTimeFormat.format(new Date(runtimeBean.getStartTime())));
    variables.put("uptime-in-ms", runtimeBean.getUptime() + "");
    variables.put("vm-name", runtimeBean.getVmName());
    variables.put("vm-vender", runtimeBean.getVmVendor());
    variables.put("vm-version", runtimeBean.getVmVersion());

    //BuildServer Version and Id
    variables.put("buildserver-version", GitBuildId.getVersion() + "");
    variables.put("buildserver-git-fingerprint", GitBuildId.getFingerprint() + "");

    // OS
    OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
    variables.put("os-arch", osBean.getArch());
    variables.put("os-name", osBean.getName());
    variables.put("os-version", osBean.getVersion());
    variables.put("num-processors", osBean.getAvailableProcessors() + "");
    variables.put("load-average-past-1-min", osBean.getSystemLoadAverage() + "");

    // Memory
    Runtime runtime = Runtime.getRuntime();
    MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    variables.put("total-memory", runtime.totalMemory() + "");
    variables.put("free-memory", runtime.freeMemory() + "");
    variables.put("max-memory", runtime.maxMemory() + "");
    variables.put("used-heap", memoryBean.getHeapMemoryUsage().getUsed() + "");
    variables.put("used-non-heap", memoryBean.getNonHeapMemoryUsage().getUsed() + "");

    // Build requests
    variables.put("count-async-build-requests", asyncBuildRequests.get() + "");
    variables.put("rejected-async-build-requests", rejectedAsyncBuildRequests.get() + "");
    variables.put("successful-async-build-requests", successfulBuildRequests.get() + "");
    variables.put("failed-async-build-requests", failedBuildRequests.get() + "");

    // Build tasks
    int max = buildExecutor.getMaxActiveTasks();
    if (max == 0) {
      variables.put("maximum-simultaneous-build-tasks-allowed", "unlimited");
    } else {
      variables.put("maximum-simultaneous-build-tasks-allowed", max + "");
    }
    variables.put("completed-build-tasks", buildExecutor.getCompletedTaskCount() + "");
    maximumActiveBuildTasks = Math.max(maximumActiveBuildTasks, buildExecutor.getActiveTaskCount());
    variables.put("maximum-simultaneous-build-tasks-occurred", maximumActiveBuildTasks + "");
    variables.put("active-build-tasks", buildExecutor.getActiveTaskCount() + "");

    StringBuilder html = new StringBuilder();
    html.append("<html><body><tt>");
    for (Map.Entry<String, String> variable : variables.entrySet()) {
      html.append("<b>").append(variable.getKey()).append("</b> ")
        .append(variable.getValue()).append("<br>");
    }
    html.append("</tt></body></html>");
    return Response.ok(html.toString(), MediaType.TEXT_HTML_TYPE).build();
  }

  /**
   * Build an APK file from the input zip file. The zip file needs to be a variant of the same
   * App Inventor source zip that's generated by the Download Source command.  The differences are
   * that it might not contain a .yail file (in which case we will generate the YAIL code) and it
   * might contain an android.keystore file at the top level (in which case we will use it to sign
   * the APK.  If there is no android.keystore file in the zip we will generate one.
   *
   * @param userName  The user name to be used in making the CN entry in the generated keystore
   * @param zipFile
   * @return the APK file
   */
  @POST
  @Path("build-from-zip")
  @Produces("application/vnd.android.package-archive;charset=utf-8")
  public Response buildFromZipFile(@QueryParam("uname") String userName, File zipFile)
    throws IOException {
    // Set the inputZip field so we can delete the input zip file later in cleanUp.
    inputZip = zipFile;
    inputZip.deleteOnExit();  // In case build server is killed before cleanUp executes.

    if(!commandLineOptions.debug)
      return Response.status(Response.Status.FORBIDDEN).type(MediaType.TEXT_PLAIN_TYPE)
        .entity("Entry point unavailable unless debugging.").build();

    try {
      build(userName, zipFile);
      String attachedFilename = outputApk.getName();
      FileInputStream outputApkDeleteOnClose = new DeleteFileOnCloseFileInputStream(outputApk);
      // Set the outputApk field to null so that it won't be deleted in cleanUp().
      outputApk = null;
      return Response.ok(outputApkDeleteOnClose)
        .header("Content-Disposition", "attachment; filename=\"" + attachedFilename + "\"")
        .build();
    } finally {
      cleanUp();
    }
  }

  /**
   * Build an APK file from the input zip file. The zip file needs to be a variant of the same
   * App Inventor source zip that's generated by the Download Source command.  The differences are
   * that it might not contain a .yail file (in which case we will generate the YAIL code) and it
   * might contain an android.keystore file at the top level (in which case we will use it to sign
   * the APK.  If there is no android.keystore file in the zip we will generate one and return it
   * in along with the APK file.
   *
   * We'll respond to the requester with a zip file containing the build.out and build.err files as
   * well as the APK file if the build succeeded and the android.keystore file if it was not
   * provided in the input zip
   *
   * @param userName  The user name to be used in making the CN entry in the generated keystore
   * @param inputZipFile  The zip file representing the App Inventor source code.
   * @return an "OK" {@link Response}.
   */
  @POST
  @Path("build-all-from-zip")
  @Produces("application/zip;charset=utf-8")
  public Response buildAllFromZipFile(@QueryParam("uname") String userName, File inputZipFile)
    throws IOException, JSONException {
    // Set the inputZip field so we can delete the input zip file later in cleanUp.
    inputZip = inputZipFile;
    inputZip.deleteOnExit();  // In case build server is killed before cleanUp executes.

    if(!commandLineOptions.debug)
      return Response.status(Response.Status.FORBIDDEN).type(MediaType.TEXT_PLAIN_TYPE)
        .entity("Entry point unavailable unless debugging.").build();

    try {
      buildAndCreateZip(userName, inputZipFile);
      String attachedFilename = outputZip.getName();
      FileInputStream outputZipDeleteOnClose = new DeleteFileOnCloseFileInputStream(outputZip);
      // Set the outputZip field to null so that it won't be deleted in cleanUp().
      outputZip = null;
      return Response.ok(outputZipDeleteOnClose)
        .header("Content-Disposition", "attachment; filename=\"" + attachedFilename + "\"")
        .build();
    } finally {
      cleanUp();
    }
  }

  /**
   * Asynchronously build an APK file from the input zip file and then send it to the callbackUrl.
   * The input zip file needs to be a variant of the same App Inventor source zip that's generated
   * by the Download Source command.  The differences are that it might not contain a .yail file (in
   * which case we will generate the YAIL code) and it might contain an android.keystore file at the
   * top level (in which case we will use it to sign the APK).
   *
   * We'll use the callbackUrlStr to post back a zip file containing the build.out and build.err
   * files as well as the APK file if the build succeeded and the android.keystore file if it was
   * not provided in the input zip
   *
   * Before building the app, we'll check that the gitBuildVersion parameter (if present) equals
   * GitBuildId.getVersion(). If the values are different, we won't even try to build
   * the app. This may seem too strict, but we need to make sure that when we build apps, we use
   * the same version of the code that loads the .blk and .scm files, the same version of
   * runtime.scm, and the same version of the App Inventor component classes.
   *
   * The status code returned here will be seen by the server in YoungAndroidProjectService.build
   * as connection.getResponseCode().
   *
   * @param userName  The user name to be used in making the CN entry in the generated keystore.
   * @param gitBuildVersion  The value of GitBuildId.getVersion() sent from
   *     YoungAndroidProjectService.build.
   * @param callbackUrlStr An url to send the build results back to.
   * @param inputZipFile  The zip file representing the App Inventor source code.
   * @return a status response, typically OK (200) or SERVICE_UNAVAILABLE (503).
   */
  @POST
  @Path("build-all-from-zip-async")
  @Produces(MediaType.TEXT_PLAIN)
  public Response buildAllFromZipFileAsync(
    @QueryParam("uname") final String userName,
    @QueryParam("callback") final String callbackUrlStr,
    @QueryParam("gitBuildVersion") final String gitBuildVersion,
    @QueryParam("actionName") final String actionName,
    final File inputZipFile) throws IOException {
    // Set the inputZip field so we can delete the input zip file later in
    // cleanUp.
    inputZip = inputZipFile;
    inputZip.deleteOnExit(); // In case build server is killed before cleanUp executes.
    String requesting_host = (new URL(callbackUrlStr)).getHost();
    LOG.info("*************in the buildserver with actionName: "+actionName+"*****************"+inputZipFile.getName());
    //for the request for update part, the file should be empty
    if (inputZip.length() == 0L) {
      cleanUp();
    } else {
      if (commandLineOptions.requiredHosts != null) {
        boolean oktoproceed = false;
        for (String host : commandLineOptions.requiredHosts) {
          if (host.equals(requesting_host)) {
            oktoproceed = true;
            break;}
        }

        if (oktoproceed) {
          LOG.info("requesting host (" + requesting_host + ") is in the allowed host list request will be honored.");
        } else {
          // Return an error
          LOG.info("requesting host (" + requesting_host + ") is NOT in the allowed host list request will be rejected.");
          return Response.status(Response.Status.FORBIDDEN).type(MediaType.TEXT_PLAIN_TYPE).entity("You are not permitted to use this build server.").build();
        }
      } else {
        LOG.info("requiredHosts is not set, no restriction on callback url.");
      }

      asyncBuildRequests.incrementAndGet();

      if (gitBuildVersion != null && !gitBuildVersion.isEmpty()) {
        if (!gitBuildVersion.equals(GitBuildId.getVersion())) {
          // This build server is not compatible with the App Inventor instance. Log this as severe
          // so the owner of the build server will know about it.
          String errorMessage = "Build server version " + GitBuildId.getVersion() +
            " is not compatible with App Inventor version " + gitBuildVersion + ".";
          LOG.severe(errorMessage);
          // This request was rejected because the gitBuildVersion parameter did not equal the
          // expected value.
          rejectedAsyncBuildRequests.incrementAndGet();
          cleanUp();
          // Here, we use CONFLICT (response code 409), which means (according to rfc2616, section
          // 10) "The request could not be completed due to a conflict with the current state of the
          // resource."
          return Response.status(Response.Status.CONFLICT).type(MediaType.TEXT_PLAIN_TYPE).entity(errorMessage).build();
        }
      }

      Runnable buildTask = new Runnable() {
          @Override
          public void run() {
            int count = buildCount.incrementAndGet();
            try {
              LOG.info("START NEW BUILD " + count);
              checkMemory();
              buildAndCreateZip(userName, inputZipFile);
              // Send zip back to the callbackUrl
              LOG.info("CallbackURL: " + callbackUrlStr);
              URL callbackUrl = new URL(callbackUrlStr);
              HttpURLConnection connection = (HttpURLConnection) callbackUrl.openConnection();
              connection.setDoOutput(true);
              connection.setRequestMethod("POST");
              // Make sure we aren't misinterpreted as
              // form-url-encoded
              connection.addRequestProperty("Content-Type","application/zip; charset=utf-8");
              connection.setConnectTimeout(60000);
              connection.setReadTimeout(60000);
              BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(connection.getOutputStream());
              try {
                BufferedInputStream bufferedInputStream = new BufferedInputStream(
                  new FileInputStream(outputZip));
                LOG.info("***********((((("+outputApk.getName()+")))))))))))");
                ///here we will post
                try {
                  ByteStreams.copy(bufferedInputStream,bufferedOutputStream);
                  checkMemory();
                  bufferedOutputStream.flush();
                } finally {
                  bufferedInputStream.close();
                }
              } finally {
                bufferedOutputStream.close();
              }
              if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {LOG.severe("Bad Response Code!: "+ connection.getResponseCode());
                // TODO(user) Maybe do some retries
              }
            } catch (Exception e) {
              // TODO(user): Maybe send a failure callback
              LOG.severe("Exception: " + e.getMessage()+ " and the length is of inputZip is "+ inputZip.length());
            } finally {
              cleanUp();
              checkMemory();
              LOG.info("BUILD " + count + " FINISHED");
            }
          }
        };
      try {
        buildExecutor.execute(buildTask);
      } catch (RejectedExecutionException e) {
        // This request was rejected because all threads in the build
        // executor are busy.
        rejectedAsyncBuildRequests.incrementAndGet();
        cleanUp();
        // Here, we use SERVICE_UNAVAILABLE (response code 503), which
        // means (according to rfc2616, section 10) "The server is
        // currently unable to handle the request due to a temporary
        // overloading or maintenance of the server. The implication
        // is that this is a temporary condition which will be
        // alleviated after some delay."
        return Response.status(Response.Status.SERVICE_UNAVAILABLE).type(MediaType.TEXT_PLAIN_TYPE).entity("The build server is currently at maximum capacity.").build();
      }
    }
    return Response.ok().type(MediaType.TEXT_PLAIN_TYPE)
      .entity("" + projectBuilder.getProgress()).build();
  }

  private void buildAndCreateZip(String userName, File inputZipFile)
    throws IOException, JSONException {
    Result buildResult = build(userName, inputZipFile);
    boolean buildSucceeded = buildResult.succeeded();
    outputZip = File.createTempFile(inputZipFile.getName(), ".zip");
    outputZip.deleteOnExit();  // In case build server is killed before cleanUp executes.
    ZipOutputStream zipOutputStream =
      new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outputZip)));
    if (buildSucceeded) {
      if (outputKeystore != null) {
        zipOutputStream.putNextEntry(new ZipEntry(outputKeystore.getName()));
        Files.copy(outputKeystore, zipOutputStream);
      }
      zipOutputStream.putNextEntry(new ZipEntry(outputApk.getName()));
      Files.copy(outputApk, zipOutputStream);
      successfulBuildRequests.getAndIncrement();
    } else {
      LOG.severe("Build " + buildCount.get() + " Failed: " + buildResult.getResult() + " " + buildResult.getError());
      failedBuildRequests.getAndIncrement();
    }
    zipOutputStream.putNextEntry(new ZipEntry("build.out"));
    String buildOutputJson = genBuildOutput(buildResult);
    PrintStream zipPrintStream = new PrintStream(zipOutputStream);
    zipPrintStream.print(buildOutputJson);
    zipPrintStream.flush();
    zipOutputStream.flush();
    zipOutputStream.close();
  }

  private String genBuildOutput(Result buildResult) throws JSONException {
    JSONObject buildOutputJsonObj = new JSONObject();
    buildOutputJsonObj.put("result", buildResult.getResult());
    buildOutputJsonObj.put("error", buildResult.getError());
    buildOutputJsonObj.put("output", buildResult.getOutput());
    if (buildResult.getFormName() != null) {
      buildOutputJsonObj.put("formName", buildResult.getFormName());
    }
    return buildOutputJsonObj.toString();
  }

  private Result build(String userName, File zipFile) throws IOException {
    outputDir = Files.createTempDir();
    // We call outputDir.deleteOnExit() here, in case build server is killed before cleanUp
    // executes. However, it is likely that the directory won't be empty and therefore, won't
    // actually be deleted. That's only if the build server is killed (via ctrl+c) while a build
    // is happening, so we should be careful about that.
    outputDir.deleteOnExit();
    Result buildResult = projectBuilder.build(userName, new ZipFile(zipFile), outputDir, false, false,
      commandLineOptions.childProcessRamMb, commandLineOptions.dexCacheDir);
    String buildOutput = buildResult.getOutput();
    LOG.info("Build output: " + buildOutput);
    String buildError = buildResult.getError();
    LOG.info("Build error output: " + buildError);
    outputApk = projectBuilder.getOutputApk();
    if (outputApk != null) {
      outputApk.deleteOnExit();  // In case build server is killed before cleanUp executes.
    }
    outputKeystore = projectBuilder.getOutputKeystore();
    if (outputKeystore != null) {
      outputKeystore.deleteOnExit();  // In case build server is killed before cleanUp executes.
    }
    checkMemory();
    return buildResult;
  }

  private void cleanUp() {
    if (inputZip != null) {
      inputZip.delete();
    }
    if (outputKeystore != null) {
      outputKeystore.delete();
    }
    if (outputApk != null) {
      outputApk.delete();
    }
    if (outputZip != null) {
      outputZip.delete();
    }
    if (outputDir != null) {
      outputDir.delete();
    }
  }

  private static void checkMemory() {
    MemoryMXBean mBean = ManagementFactory.getMemoryMXBean();
    mBean.gc();
    LOG.info("Build " + buildCount + " current used memory: "
      + mBean.getHeapMemoryUsage().getUsed() + " bytes");
  }

  public static void main(String[] args) throws IOException {
    // TODO(markf): Eventually we'll figure out how to appropriately start and stop the server when
    // it's run in a production environment.   For now, just kill the process

    CmdLineParser cmdLineParser = new CmdLineParser(commandLineOptions);
    try {
      cmdLineParser.parseArgument(args);
    } catch (CmdLineException e) {
      LOG.severe(e.getMessage());
      cmdLineParser.printUsage(System.err);
      System.exit(1);
    }

    // Now that the command line options have been processed, we can create the buildExecutor.
    buildExecutor = new NonQueuingExecutor(commandLineOptions.maxSimultaneousBuilds);

    int port = commandLineOptions.port;
    SelectorThread threadSelector = GrizzlyServerFactory.create("http://localhost:" + port + "/");
    String hostAddress = InetAddress.getLocalHost().getHostAddress();
    LOG.info("App Inventor Build Server - Version: " + GitBuildId.getVersion());
    LOG.info("App Inventor Build Server - Git Fingerprint: " + GitBuildId.getFingerprint());
    LOG.info("Running at: http://" + hostAddress + ":" + port + "/buildserver");
    if (commandLineOptions.maxSimultaneousBuilds == 0) {
      LOG.info("Maximum simultanous builds = unlimited!");
    } else {
      LOG.info("Maximum simultanous builds = " + commandLineOptions.maxSimultaneousBuilds);
    }
    LOG.info("Visit: http://" + hostAddress + ":" + port +
      "/buildserver/health for server health");
    LOG.info("Visit: http://" + hostAddress + ":" + port +
      "/buildserver/vars for server values");
    LOG.info("Server running");
  }

  private static class DeleteFileOnCloseFileInputStream extends FileInputStream {
    private final File file;

    DeleteFileOnCloseFileInputStream(File file) throws IOException {
      super(file);
      this.file = file;
    }
    @Override
    public void close() throws IOException {
      super.close();
      file.delete();
    }
  }
}
