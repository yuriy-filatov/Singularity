package com.hubspot.singularity.scheduler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.inject.Singleton;

import org.apache.mesos.v1.Protos.Offer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.Inject;
import com.hubspot.mesos.JavaUtils;
import com.hubspot.singularity.SingularityAction;
import com.hubspot.singularity.config.SingularityConfiguration;
import com.hubspot.singularity.data.DisasterManager;
import com.hubspot.singularity.mesos.OfferCache;
import com.hubspot.singularity.mesos.SingularityMesosOfferScheduler;
import com.hubspot.singularity.mesos.SingularityMesosScheduler;
import com.hubspot.singularity.mesos.SingularityOfferCache.CachedOffer;
import com.hubspot.singularity.mesos.SingularityOfferHolder;
import com.hubspot.singularity.mesos.SingularitySchedulerLock;

@Singleton
public class SingularitySchedulerPoller extends SingularityLeaderOnlyPoller {

  private static final Logger LOG = LoggerFactory.getLogger(SingularitySchedulerPoller.class);

  private final OfferCache offerCache;
  private final SingularityMesosScheduler scheduler;
  private final SingularityMesosOfferScheduler offerScheduler;
  private final DisasterManager disasterManager;

  @Inject
  SingularitySchedulerPoller(SingularityMesosOfferScheduler offerScheduler, OfferCache offerCache, SingularityMesosScheduler scheduler,
                             SingularityConfiguration configuration, SingularitySchedulerLock lock, DisasterManager disasterManager) {
    super(configuration.getCheckSchedulerEverySeconds(), TimeUnit.SECONDS, lock, true);

    this.offerCache = offerCache;
    this.offerScheduler = offerScheduler;
    this.scheduler = scheduler;
    this.disasterManager = disasterManager;
  }

  @Override
  public void runActionOnPoll() {
    if (disasterManager.isDisabled(SingularityAction.RUN_SCHEDULER_POLLER)) {
      LOG.warn("Scheduler poller is disabled");
      return;
    }
    final long start = System.currentTimeMillis();

    List<CachedOffer> cachedOffers = offerCache.checkoutOffers();
    Map<String, CachedOffer> offerIdToCachedOffer = new HashMap<>(cachedOffers.size());
    List<Offer> offers = new ArrayList<>(cachedOffers.size());

    for (CachedOffer cachedOffer : cachedOffers) {
      offerIdToCachedOffer.put(cachedOffer.getOfferId(), cachedOffer);
      offers.add(cachedOffer.getOffer());
    }

    List<SingularityOfferHolder> offerHolders = offerScheduler.checkOffers(offers);

    if (offerHolders.isEmpty()) {
      return;
    }

    if (!scheduler.isRunning()) {
      LOG.error("No active scheduler present, can't accept cached offers");
      return;
    }

    int acceptedOffers = 0;
    int launchedTasks = 0;

    for (SingularityOfferHolder offerHolder : offerHolders) {
      CachedOffer cachedOffer = offerIdToCachedOffer.get(offerHolder.getOffer().getId().getValue());

      if (!offerHolder.getAcceptedTasks().isEmpty()) {
        offerHolder.launchTasks(scheduler);
        launchedTasks += offerHolder.getAcceptedTasks().size();
        acceptedOffers++;
        offerCache.useOffer(cachedOffer);
      } else {
        offerCache.returnOffer(cachedOffer);
      }
    }

    LOG.info("Launched {} tasks on {} cached offers (returned {}) in {}", launchedTasks, acceptedOffers, offerHolders.size() - acceptedOffers, JavaUtils.duration(start));
  }
}
