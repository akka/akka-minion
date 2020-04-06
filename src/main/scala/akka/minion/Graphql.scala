package akka.minion

import akka.actor.ActorSystem
import akka.minion.App.Settings
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

import scala.concurrent.Await
import scala.concurrent.duration._

object Graphql extends DefaultJsonProtocol {
  case class Owner(login: String)
  case class Total(totalCount: Int) {
    def pretty = f"$totalCount%6d"
  }
  case class StatsDataRepo(
      owner: Owner,
      name: String,
      issuesTotal: Total,
      issuesOpen: Total,
      issuesFailed: Total,
      prTotal: Total,
      prOpen: Total
  ) {
    def pretty() =
      s""" ${owner.login}/$name
         | Total issues   ${issuesTotal.pretty}
         | Open issues    ${issuesOpen.pretty}
         | Open failures  ${issuesFailed.pretty}
         |
         | Total PRs      ${prTotal.pretty}
         | Open PRs       ${prOpen.pretty}
         |""".stripMargin

    def markdown() =
      s"""||${owner.login}/$name | |
          ||--------------|--|
          ||Total issues  | ${issuesTotal.pretty} |
          ||Open issues   | ${issuesOpen.pretty} |
          ||Open failures | ${issuesFailed.pretty} |
          ||Total PRs     | ${prTotal.pretty} |
          ||Open PRs      | ${prOpen.pretty} |
          |""".stripMargin
  }
  case class StatsData(repo: StatsDataRepo)
  case class Stats(data: StatsData)

  private type RJF[x] = RootJsonFormat[x]

  implicit lazy val _fmtOwner: RJF[Owner] = jsonFormat1(Owner)
  implicit lazy val _fmtTotal: RJF[Total] = jsonFormat1(Total)
  implicit lazy val _fmtStatsDataRepo: RJF[StatsDataRepo] = jsonFormat7(StatsDataRepo)
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
      |    issuesFailed: issues(states: OPEN, labels: "failed") {
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
    val allRepos = settings.teamRepos.values.fold(Set.empty)(_ ++ _).toList.sorted
    for {
      r <- allRepos
      Array(owner, name) = r.split('/')
    } {
      val repoFuture = graphql[Stats](statsQuery(owner, name))
      println(Await.result(repoFuture, 10.seconds).data.repo.markdown())
      println()
    }
  }

}
