package io.buoyant.namerd

import com.twitter.finagle.stats.StatsReceiver
import com.twitter.finagle.{Path, Namer, Stack}
import com.twitter.finagle.stats.{BroadcastStatsReceiver, LoadedStatsReceiver}
import com.twitter.finagle.tracing.{BroadcastTracer, DefaultTracer}
import com.twitter.finagle.util.LoadService
import io.buoyant.admin.AdminConfig
import io.buoyant.config.{ConfigError, ConfigInitializer, Parser}
import io.buoyant.config.types.Port
import io.buoyant.namer.{NamerConfig, NamerInitializer}
import io.buoyant.telemetry._
import scala.util.control.NoStackTrace

private[namerd] case class NamerdConfig(
  admin: Option[AdminConfig],
  storage: DtabStoreConfig,
  namers: Seq[NamerConfig],
  interfaces: Seq[InterfaceConfig],
  telemetry: Option[Seq[TelemeterConfig]]
) {
  require(namers != null, "'namers' field is required")
  require(interfaces != null, "'interfaces' field is required")
  require(interfaces.nonEmpty, "One or more interfaces must be specified")
  import NamerdConfig._

  def mk(): Namerd = {
    if (storage.disabled) {
      val msg = s"The ${storage.getClass.getName} storage is experimental and must be " +
        "explicitly enabled by setting the `experimental' parameter to true."
      throw new IllegalArgumentException(msg) with NoStackTrace
    }

    val telemeters = telemetry match {
      case None => Seq(new TwitterMetricsTelemeter)
      case Some(configs) => configs.map(_.mk(Stack.Params.empty))
    }

    val stats = {
      val receivers = telemeters.collect { case t if !t.stats.isNull => t.stats }
      BroadcastStatsReceiver(receivers)
    }
    LoadedStatsReceiver.self = stats

    val tracer = {
      val tracers = telemeters.collect { case t if !t.tracer.isNull => t.tracer }
      BroadcastTracer(tracers)
    }
    DefaultTracer.self = tracer

    val dtabStore = storage.mkDtabStore
    val namersByPfx = mkNamers()
    val ifaces = mkInterfaces(dtabStore, namersByPfx, stats)
    val adminImpl = admin.getOrElse(DefaultAdminConfig).mk()
    new Namerd(ifaces, dtabStore, namersByPfx, adminImpl, telemeters)
  }

  private[this] def mkNamers(): Map[Path, Namer] =
    namers.foldLeft(Map.empty[Path, Namer]) {
      case (namers, config) =>
        if (config.prefix.isEmpty)
          throw NamerdConfig.EmptyNamerPrefix

        for (prefix <- namers.keys)
          if (prefix.startsWith(config.prefix) || config.prefix.startsWith(prefix))
            throw NamerdConfig.ConflictingNamers(prefix, config.prefix)

        namers + (config.prefix -> config.newNamer(Stack.Params.empty))
    }

  private[this] def mkInterfaces(
    dtabStore: DtabStore,
    namersByPfx: Map[Path, Namer],
    stats: StatsReceiver
  ): Seq[Servable] =
    interfaces.map(_.mk(dtabStore, namersByPfx, stats))
}

private[namerd] object NamerdConfig {

  private def DefaultAdminConfig = AdminConfig(Port(9991))

  case class ConflictingNamers(prefix0: Path, prefix1: Path) extends ConfigError {
    lazy val message =
      s"Namers must not have overlapping prefixes: ${prefix0.show} & ${prefix1.show}"
  }

  object EmptyNamerPrefix extends ConfigError {
    lazy val message = s"Namers must not have an empty prefix"
  }

  private[namerd] case class Initializers(
    namer: Seq[NamerInitializer] = Nil,
    dtabStore: Seq[DtabStoreInitializer] = Nil,
    iface: Seq[InterfaceInitializer] = Nil,
    telemetry: Seq[TelemeterInitializer] = Nil
  ) {
    def iter: Iterable[Seq[ConfigInitializer]] =
      Seq(namer, dtabStore, iface, telemetry)
  }

  private[namerd] lazy val LoadedInitializers = Initializers(
    LoadService[NamerInitializer],
    LoadService[DtabStoreInitializer],
    LoadService[InterfaceInitializer],
    LoadService[TelemeterInitializer]
  )

  def loadNamerd(configText: String, initializers: Initializers): NamerdConfig = {
    val mapper = Parser.objectMapper(configText, initializers.iter)
    mapper.readValue[NamerdConfig](configText)
  }

  def loadNamerd(configText: String): NamerdConfig =
    loadNamerd(configText, LoadedInitializers)
}
