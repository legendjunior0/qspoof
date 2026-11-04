update private.premium_config
set banner_slot_limit = 0,
    launch_interstitials_per_day = 1
where singleton = true;
