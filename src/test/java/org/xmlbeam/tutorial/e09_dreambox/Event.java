/**
 *  Copyright 2012 Sven Ewald
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.xmlbeam.tutorial.e09_dreambox;

import org.xmlbeam.annotation.XBRead;
/**
 * @author <a href="https://github.com/SvenEwald">Sven Ewald</a>
 */
public interface Event {

    @XBRead("//e2eventid")
    String getID();

    @XBRead("//e2eventstart * 1000")
    long getStart();

    @XBRead("//e2eventduration div 60")
    long getDurationInMinutes();

    @XBRead("//e2eventcurrenttime")
    long getCurrentTime();

    @XBRead("//e2eventtitle")
    String getTitle();

    @XBRead("//e2eventdescription")
    String getDescription();

    @XBRead("//e2eventdescriptionextended")
    String getDescriptionExtended();

    @XBRead("//e2eventservicereference")
    String getServiceReference();

    @XBRead("//e2eventservicename")
    String getServiceName();
}
