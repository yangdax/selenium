
package org.openqa.selenium.remote.server;

import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.openqa.grid.common.RegistrationRequest;
import org.openqa.grid.internal.Registry;
import org.openqa.grid.internal.TestSession;
import org.openqa.grid.selenium.proxy.DefaultRemoteProxy;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.openqa.selenium.remote.server.NodeShutDownServlet;

/**
 * This is a custom Proxy. This proxy when injected into the Grid, starts counting unique test sessions.
 * After "n" test sessions, the proxy unhooks the node gracefully from the grid and self terminates gracefully. The
 * number of unique sessions is controlled via a properties file : "mygrid.properties". A typical content of the
 * file would be as below :
 *
 * <pre>
 * UniqueSessionCount = 2
 * </pre>
 *
 * Here UniqueSessionCount represents the max. number of tests that the node will run before recycling itself.
 */

public class CustomRemoteProxy extends DefaultRemoteProxy {


  private volatile int counter;

  private MyNodePoller nodePollingThread = null;

  public CustomRemoteProxy(RegistrationRequest request, Registry registry) throws IOException {
    super(request, registry);
    System.out.println("New proxy instantiated for the machine :" + getRemoteHost().getHost());
    InputStream stream = CustomRemoteProxy.class.getResourceAsStream("/mygrid.properties");
    Properties props = new Properties();
    props.load(stream);
    counter = Integer.parseInt((String) props.get("UniqueSessionCount"));
  }

  @Override
  public void startPolling() {
    super.startPolling();
    nodePollingThread = new MyNodePoller(this);
    nodePollingThread.start();
  }

  @Override
  public void stopPolling() {
    super.stopPolling();
    nodePollingThread.interrupt();
  }

  /**
   * Decrement the counter till it reaches zero.
   *
   * @return - True if decrementing didn't result in the counter becoming zero.
   */
  private synchronized boolean decrementCounter() {
    if (this.counter == 0) {
      return false;
    }
    --this.counter;
    return true;
  }

  /**
   * Invoke this method to decide if the node has reached its max. test execution value and if the node should be
   * picked up for recycling.
   *
   * @return - <code>true</code> if the node can be released and shutdown as well.
   */
  public synchronized boolean shouldNodeBeReleased() {
    if (this.counter == 0) {
      System.out.println("The node " + getRemoteHost().getHost() + "can be released now");
      return true;
    }
    return false;
  }

  @Override
  public void beforeSession(TestSession session) {
    String ip = getRemoteHost().getHost();
    if (decrementCounter()) {
      super.beforeSession(session);
    } else {
      System.out.println("Cannot forward any more tests to this proxy " + ip);
      return;
    }
  }

  /**
   * This class is used to poll continuously to decide if the current node can be cleaned up. If it can be cleaned up,
   * this class helps in un-hooking the node from the grid and also issuing a shutdown request to the node.
   */
  static class MyNodePoller extends Thread {
    private CustomRemoteProxy proxy = null;

    public MyNodePoller(CustomRemoteProxy proxy) {
      this.proxy = proxy;
    }

    @Override
    public void run() {
      while (true) {
        boolean isBusy = proxy.isBusy();
        boolean canRelease = proxy.shouldNodeBeReleased();
        if (!isBusy && canRelease) {
          proxy.getRegistry().removeIfPresent(proxy);
          System.out.println(proxy.getRemoteHost().getHost() + " has been released successfully from the hub");
          shutdownNode();
          return;
        }
        try {
          Thread.sleep(10000);
        } catch (InterruptedException e) {
          return;
        }
      }
    }

    private void shutdownNode() {
      HttpClient client = new DefaultHttpClient();
      StringBuilder url = new StringBuilder();
      url.append("http://");
      url.append(proxy.getRemoteHost().getHost());
      url.append(":").append(proxy.getRemoteHost().getPort());
      url.append("/extra/");
      url.append(NodeShutDownServlet.class.getSimpleName());
      HttpPost post = new HttpPost(url.toString());
      try {
        client.execute(post);
      } catch (ClientProtocolException e) {
        //logging should occur here
      } catch (IOException e) {
        //logging should occur here
      }
      System.out.println("Node " + proxy.getRemoteHost().getHost() + " shut-down successfully.");
    }
  }
}
