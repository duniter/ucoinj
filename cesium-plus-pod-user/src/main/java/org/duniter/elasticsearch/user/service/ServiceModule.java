package org.duniter.elasticsearch.user.service;

/*
 * #%L
 * duniter4j-elasticsearch-plugin
 * %%
 * Copyright (C) 2014 - 2016 EIS
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public 
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.Module;

public class ServiceModule extends AbstractModule implements Module {

    @Override protected void configure() {
        bind(DeleteHistoryService.class).asEagerSingleton();

        bind(MessageService.class).asEagerSingleton();
        bind(UserEventService.class).asEagerSingleton();
        bind(UserService.class).asEagerSingleton();
        bind(GroupService.class).asEagerSingleton();
        bind(PageService.class).asEagerSingleton();

        bind(AdminService.class).asEagerSingleton();
        bind(MailService.class).asEagerSingleton();
        bind(UserInvitationService.class).asEagerSingleton();
        bind(LikeService.class).asEagerSingleton();

        // User events
        bind(BlockchainUserEventService.class).asEagerSingleton();
        bind(PageCommentUserEventService.class).asEagerSingleton();

        // Monitor BMA node
        bind(BlockchainMonitoringService.class).asEagerSingleton();
    }

    /* protected methods */

}