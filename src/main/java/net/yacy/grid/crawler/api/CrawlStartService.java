/**
 *  CrawlStartService
 *  Copyright 12.6.2017 by Michael Peter Christen, @0rb1t3r
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package net.yacy.grid.crawler.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.regex.Pattern;

import javax.servlet.http.HttpServletResponse;

import org.json.JSONArray;
import org.json.JSONObject;

import ai.susi.mind.SusiAction;
import ai.susi.mind.SusiThought;
import net.yacy.grid.YaCyServices;
import net.yacy.grid.contracts.User;
import net.yacy.grid.crawler.CrawlerListener;
import net.yacy.grid.http.APIHandler;
import net.yacy.grid.http.ObjectAPIHandler;
import net.yacy.grid.http.Query;
import net.yacy.grid.http.ServiceResponse;
import net.yacy.grid.io.index.CrawlerMapping;
import net.yacy.grid.io.index.CrawlstartDocument;
import net.yacy.grid.io.index.CrawlstartMapping;
import net.yacy.grid.io.index.GridIndex;
import net.yacy.grid.io.index.Index.QueryLanguage;
import net.yacy.grid.io.index.WebMapping;
import net.yacy.grid.io.messages.GridQueue;
import net.yacy.grid.io.messages.ShardingMethod;
import net.yacy.grid.mcp.Service;
import net.yacy.grid.tools.Digest;
import net.yacy.grid.tools.Domains;
import net.yacy.grid.tools.JSONList;
import net.yacy.grid.tools.Logger;
import net.yacy.grid.tools.MultiProtocolURL;

/**
 *
 * Test URL:
 * http://localhost:8300/yacy/grid/crawler/crawlStart.json?crawlingURL=yacy.net&indexmustnotmatch=.*Mitmachen.*&mustmatch=.*yacy.net.*
 * http://localhost:8300/yacy/grid/crawler/crawlStart.json?crawlingURL=ix.de&crawlingDepth=6&priority=true
 * http://localhost:8300/yacy/grid/crawler/crawlStart.json?crawlingURL=tagesschau.de&loaderHeadless=false
 *
 * then check crawl queue status at http://localhost:15672/
 * default account is guest:guest
 */
public class CrawlStartService extends ObjectAPIHandler implements APIHandler {

    private static final long serialVersionUID = 8578474303031749879L;
    public static final String NAME = "crawlStart";

    @Override
    public String getAPIPath() {
        return "/yacy/grid/crawler/" + NAME + ".json";
    }

    @Override
    public ServiceResponse serviceImpl(final Query call, final HttpServletResponse response) {
        final JSONObject crawlstart = CrawlerDefaultValuesService.crawlStartDefaultClone();

        // read call attributes using the default crawlstart key names
        final String userId = call.get("userId", User.ANONYMOUS_ID);
        for (final String key: crawlstart.keySet()) {
            final Object object = crawlstart.get(key);
            if (object instanceof String) crawlstart.put(key, call.get(key, crawlstart.getString(key)));
            else if (object instanceof Integer) crawlstart.put(key, call.get(key, crawlstart.getInt(key)));
            else if (object instanceof Long) crawlstart.put(key, call.get(key, crawlstart.getLong(key)));
            else if (object instanceof JSONArray) {
                final JSONArray a = crawlstart.getJSONArray(key);
                final Object cv = call.get(key);
                if (cv != null) crawlstart.put(key, cv);
            } else {
                System.out.println("unrecognized type: " + object.getClass().toString());
            }
        }
        crawlstart.put("userId", userId);

        // fix attributes
        final int crawlingDepth = crawlstart.optInt("crawlingDepth", 3);
        crawlstart.put("crawlingDepth", Math.min(crawlingDepth, 8)); // crawlingDepth shall not exceed 8 - this is used for enhanced balancing to be able to reach crawl leaves
        final String mustmatch = crawlstart.optString("mustmatch", CrawlerDefaultValuesService.defaultValues.getString("mustmatch")).trim();
        crawlstart.put("mustmatch", mustmatch);
        final Map<String, Pattern> collections = WebMapping.collectionParser(crawlstart.optString("collection").trim());

        // set the crawl id
        final CrawlerListener.CrawlstartURLSplitter crawlstartURLs = new CrawlerListener.CrawlstartURLSplitter(crawlstart.getString("crawlingURL"));
        final Date now = new Date();
        // start the crawls; each of the url in a separate crawl to enforce parallel loading from different hosts
        final SusiThought allCrawlstarts = new SusiThought();
        int count = 0;
        for (final MultiProtocolURL url: crawlstartURLs.getURLs()) {
            final JSONObject singlecrawl = new JSONObject();
            for (final String key: crawlstart.keySet()) singlecrawl.put(key, crawlstart.get(key)); // create a clone of crawlstart
            final String crawlId = CrawlerListener.getCrawlID(url, now, count++);
            final String start_url = url.toNormalform(true);
            final String start_ssld = Domains.getSmartSLD(url.getHost());
            singlecrawl.put("id", crawlId);
            singlecrawl.put("start_url", start_url);
            singlecrawl.put("start_ssld", start_ssld);

            //singlecrawl.put("crawlingURLs", new JSONArray().put(url.toNormalform(true)));

            try {
                // Create a crawlstart index entry: this will keep track of all crawls that have been started.
                // once such an entry is created, it is never changed or deleted again by any YaCy Grid process.
                final CrawlstartDocument crawlstartDoc = new CrawlstartDocument()
                        .setCrawlID(crawlId)
                        .setUserID(userId)
                        .setMustmatch(mustmatch)
                        .setCollections(collections.keySet())
                        .setCrawlstartURL(start_url)
                        .setCrawlstartSSLD(start_ssld)
                        .setInitDate(now)
                        .setData(singlecrawl);
                crawlstartDoc.store(Service.instance.config, Service.instance.config.gridIndex);

                // Create a crawler url tracking index entry: this will keep track of single urls and their status
                // While it is processed. The entry also serves as a double-check entry to terminate a crawl even if the
                // crawler is restarted.

                // delete the start url
                final String urlid = Digest.encodeMD5Hex(start_url);
                final String crawlerIndexName = Service.instance.config.properties.getOrDefault("grid.elasticsearch.indexName.crawler", GridIndex.DEFAULT_INDEXNAME_CRAWLER);
                final String crawlstartIndexName = Service.instance.config.properties.getOrDefault("grid.elasticsearch.indexName.crawlstart", GridIndex.DEFAULT_INDEXNAME_CRAWLSTART);
                long deleted = Service.instance.config.gridIndex.delete(crawlerIndexName, QueryLanguage.fields, "{ \"_id\":\"" + urlid + "\"}");
                Logger.info(this.getClass(), "deleted " + deleted + " old crawl index entries for _id");

                // Because 'old' crawls may block new ones we identify possible blocking entries by start_url
                deleted = Service.instance.config.gridIndex.delete(crawlerIndexName, QueryLanguage.fields, "{ \"" + CrawlerMapping.start_url_s.name() + ".keyword\":\"" + start_url + "\"}");
                Logger.info(this.getClass(), "deleted " + deleted + " old crawl index entries for start_url_s");
                // we do not create a crawler document entry here because that would conflict with the double check.
                // crawler documents must be written after the double check has happened.

                // create a crawl queue entry
                final GridQueue queueName = Service.instance.config.gridBroker.queueName(YaCyServices.crawler, YaCyServices.crawler.getSourceQueues(), ShardingMethod.BALANCE, CrawlerListener.CRAWLER_PRIORITY_DIMENSIONS, singlecrawl.getInt("priority"), url.getHost());
                final SusiThought json = new SusiThought();
                json.setData(new JSONArray().put(singlecrawl));
                final JSONObject action = new JSONObject()
                        .put("type", YaCyServices.crawler.name())
                        .put("queue", queueName.name())
                        .put("id", crawlId)
                        .put("user_id", userId)
                        .put("depth", 0)
                        .put("sourcegraph", "rootasset");
                final SusiAction crawlAction = new SusiAction(action);
                final JSONObject graph = new JSONObject(true).put(WebMapping.canonical_s.getMapping().name(), start_url);
                crawlAction.setJSONListAsset("rootasset", new JSONList().add(graph));
                json.addAction(crawlAction);
                allCrawlstarts.addAction(crawlAction);
                final byte[] b = json.toString().getBytes(StandardCharsets.UTF_8);
                Service.instance.config.gridBroker.send(YaCyServices.crawler, queueName, b);

            } catch (final IOException e) {
                Logger.warn(this.getClass(), "error when starting crawl for " + url.toNormalform(true), e);
                allCrawlstarts.put(ObjectAPIHandler.COMMENT_KEY, e.getMessage());
            }
        }

        // construct a crawl start message
        allCrawlstarts.setData(new JSONArray().put(crawlstart));
        allCrawlstarts.put(ObjectAPIHandler.SUCCESS_KEY, allCrawlstarts.getActions().size() > 0);

        // finally add the crawl start on the queue
        return new ServiceResponse(allCrawlstarts);
    }

}

