package akka.minion

import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter

import akka.actor.ActorSystem
import akka.minion.App.Settings
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

import scala.concurrent.Await
import scala.concurrent.duration._

object Graphql extends DefaultJsonProtocol {
  private val tab = 9.toChar

  case class Owner(login: String)
  case class Total(totalCount: Int) {
    def pretty = f"$totalCount%6d"
  }
  case class StatsDataRepo(
      owner: Owner,
      name: String,
      issuesTotal: Total,
      issuesOpen: Total,
      issuesBugTotal: Total,
      issuesBugOpen: Total,
      issuesFailedTotal: Total,
      issuesFailedOpen: Total,
      prTotal: Total,
      prOpen: Total
  ) {
    def pretty() =
      s""" ${owner.login}/$name
         | Total issues   ${issuesTotal.pretty}
         | Open issues    ${issuesOpen.pretty}
         | Total bugs     ${issuesBugTotal.pretty}
         | Open bugs      ${issuesBugOpen.pretty}
         | Total failures ${issuesFailedTotal.pretty}
         | Open failures  ${issuesFailedOpen.pretty}
         |
         | Total PRs      ${prTotal.pretty}
         | Open PRs       ${prOpen.pretty}
         |""".stripMargin

    def markdown() =
      s"""||${owner.login}/$name | |
          ||---------------|--|
          ||Total issues   | ${issuesTotal.pretty} |
          ||Open issues    | ${issuesOpen.pretty} |
          ||Total bugs     | ${issuesBugTotal.pretty} |
          ||Open bugs      | ${issuesBugOpen.pretty} |
          ||Total failures | ${issuesFailedTotal.pretty} |
          ||Open failures  | ${issuesFailedOpen.pretty} |
          ||Total PRs      | ${prTotal.pretty} |
          ||Open PRs       | ${prOpen.pretty} |
          |""".stripMargin

    def spreadsheet() =
      s"${DateTimeFormatter.ISO_DATE.format(LocalDate.now())}$tab " +
      s"${issuesTotal.totalCount - issuesOpen.totalCount}$tab " +
      s"${issuesOpen.totalCount}$tab " +
      s"${issuesBugTotal.totalCount - issuesBugOpen.totalCount}$tab " +
      s"${issuesBugOpen.totalCount}$tab " +
      s"${issuesFailedTotal.totalCount - issuesFailedOpen.totalCount}$tab " +
      s"${issuesFailedOpen.totalCount}$tab " +
      s"${prTotal.totalCount}$tab " +
      s"${prOpen.totalCount}$tab "
  }
  case class StatsData(repo: StatsDataRepo)
  case class Stats(data: StatsData)

  private type RJF[x] = RootJsonFormat[x]

  implicit lazy val _fmtOwner: RJF[Owner] = jsonFormat1(Owner)
  implicit lazy val _fmtTotal: RJF[Total] = jsonFormat1(Total)
  implicit lazy val _fmtStatsDataRepo: RJF[StatsDataRepo] = jsonFormat10(StatsDataRepo)
  implicit lazy val _fmtStatsData: RJF[StatsData] = jsonFormat1(StatsData)
  implicit lazy val _fmtStats: RJF[Stats] = jsonFormat1(Stats)

}

class Graphql(val settings: Settings)(implicit val system: ActorSystem) extends GithubCaller {

  import Graphql._

  final val GitHubUrl = "api.github.com"

  def statsQuery(repoOwner: String, repoName: String) =
    s"""query {
      |  repo: repository(name: "$repoName", owner: "$repoOwner") {
      |    owner {login}
      |    name
      |    issuesTotal: issues {
      |      totalCount
      |    }
      |    issuesOpen: issues(states: OPEN) {
      |      totalCount
      |    }
      |    issuesBugTotal: issues(labels: "bug") {
      |      totalCount
      |    }
      |    issuesBugOpen: issues(states: OPEN, labels: "bug") {
      |      totalCount
      |    }
      |    issuesFailedTotal: issues(labels: "failed") {
      |      totalCount
      |    }
      |    issuesFailedOpen: issues(states: OPEN, labels: "failed") {
      |      totalCount
      |    }
      |    prTotal: pullRequests {
      |      totalCount
      |    }
      |    prOpen: pullRequests(states: OPEN) {
      |      totalCount
      |    }
      |  }
      |}
      |""".stripMargin

  val statsReply =
    """{
      |  "data": {
      |    "repo": {
      |      "owner": {
      |        "login": "akka"
      |      },
      |      "name": "akka",
      |      "issues_total": {
      |        "totalCount": 6810
      |      },
      |      "issues_open": {
      |        "totalCount": 819
      |      },
      |      "issues_failed": {
      |        "totalCount": 41
      |      },
      |      "pr_total": {
      |        "totalCount": 9476
      |      },
      |      "pr_open": {
      |        "totalCount": 55
      |      },
      |
      |
      |""".stripMargin

  def call() = {
    val header = new StringBuilder
    val data = new StringBuilder()
    for {
      r <- settings.statsRepos
      Array(owner, name) = r.split('/')
    } {
      val repoFuture = graphql[Stats](statsQuery(owner, name))
      val stats = Await.result(repoFuture, 10.seconds)
      header.append(
        s"$r$tab Issues Total$tab Open issues$tab Bugs Closed$tab Bugs Open$tab Failures Closed$tab Failures Open$tab PR Total$tab PR Open$tab "
      )
      data.append(stats.data.repo.spreadsheet())
    }
    println(header.toString())
    println(data.toString())
  }

}
