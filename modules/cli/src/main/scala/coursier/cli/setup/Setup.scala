package coursier.cli.setup

import java.io.File
import java.util.Locale

import caseapp.core.app.CaseApp
import caseapp.core.RemainingArgs
import coursier.cli.Util.ValidatedExitOnError
import coursier.env.{EnvironmentUpdate, ProfileUpdater, WindowsEnvVarUpdater}
import coursier.install.{Channels, InstallDir}
import coursier.launcher.internal.Windows
import coursier.util.{Sync, Task}

object Setup extends CaseApp[SetupOptions] {

  def run(options: SetupOptions, args: RemainingArgs): Unit = {

    val params = SetupParams(options).exitOnError()

    val pool = Sync.fixedThreadPool(params.cache.parallel)
    val logger = params.output.logger()
    val cache = params.cache.cache(pool, logger)

    val javaHome = params.sharedJava.javaHome(cache, params.output.verbosity)

    val envVarUpdater =
      if (Windows.isWindows)
        Left(WindowsEnvVarUpdater())
      else
        Right(
          ProfileUpdater()
            .withHome(params.homeOpt.orElse(ProfileUpdater.defaultHome))
        )

    val installCache = cache.withLogger(params.output.logger(byFileType = true))
    val installDir = InstallDir(params.sharedInstall.dir, installCache)
      .withVerbosity(params.output.verbosity)
      .withGraalvmParamsOpt(params.sharedInstall.graalvmParamsOpt)
      .withCoursierRepositories(params.sharedInstall.repositories)
    val channels = Channels(params.sharedChannel.channels, params.sharedInstall.repositories, installCache)
      .withVerbosity(params.output.verbosity)

    val confirm =
      if (params.yes)
        Confirm.YesToAll()
      else
        Confirm.ConsoleInput().withIndent(2)

    val tasks = Seq(
      MaybeInstallJvm(cache, envVarUpdater, javaHome, confirm),
      MaybeSetupPath(
        installDir,
        envVarUpdater,
        EnvironmentUpdate.defaultGetEnv,
        File.pathSeparator,
        confirm
      ),
      MaybeInstallApps(installDir, channels, DefaultAppList.defaultAppList)
    )

    val task = tasks.foldLeft(Task.point(()))((acc, t) => acc.flatMap(_ => t.fullTask(System.out)))

    if (params.banner)
      // from https://github.com/scala/scala/blob/eb1ea8b367f9b240afc0b16184396fa3bbf7e37c/project/VersionUtil.scala#L34-L39
      System.out.println(
        """
          |     ________ ___   / /  ___
          |    / __/ __// _ | / /  / _ |
          |  __\ \/ /__/ __ |/ /__/ __ |
          | /____/\___/_/ |_/____/_/ | |
          |                          |/
          |""".stripMargin
      )

    // TODO Better error messages for relevant exceptions
    task.unsafeRun()(cache.ec)
  }
}