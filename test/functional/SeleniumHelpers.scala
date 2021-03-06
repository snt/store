package functional

import java.net.URI
import java.util.{ArrayList, Arrays}
import org.openqa.selenium.htmlunit.HtmlUnitDriver
import play.api.test.{TestBrowser, TestServer}
import org.openqa.selenium.WebDriver
import org.openqa.selenium.firefox.{FirefoxDriver, FirefoxProfile}

object SeleniumHelpers {
  def FirefoxJa = {
    val profile = new FirefoxProfile
    profile.setPreference("general.useragent.locale", "ja")
    profile.setPreference("intl.accept_languages", "ja, en")
    new FirefoxDriver(profile)
  }

  def running[T](testServer: TestServer, webDriver: WebDriver)(block: TestBrowser => T): T = {
    var browser: TestBrowser = null
    synchronized {
      try {
        testServer.start()
        browser = TestBrowser(webDriver, None)
        block(browser)
      } finally {
        if (browser != null) {
          browser.quit()
        }
        testServer.stop()
      }
    }
  }

  def htmlUnit(): HtmlUnitDriver = {
    val htmlUnit = new HtmlUnitDriver()
    val proxy: String = System.getenv("http_proxy")
    if (proxy != null) {
      val url: URI = new URI(proxy)
      htmlUnit.setHTTPProxy(url.getHost(), url.getPort(), new ArrayList[String](Arrays.asList("localhost")))
    }

    htmlUnit.setJavascriptEnabled(true)
    htmlUnit
  }
}
