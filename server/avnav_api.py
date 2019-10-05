#!/usr/bin/env python
# -*- coding: utf-8 -*-
# vim: ts=2 sw=2 et ai
###############################################################################
# Copyright (c) 2012-2019 Andreas Vogel andreas@wellenvogel.net
#
#  Permission is hereby granted, free of charge, to any person obtaining a
#  copy of this software and associated documentation files (the "Software"),
#  to deal in the Software without restriction, including without limitation
#  the rights to use, copy, modify, merge, publish, distribute, sublicense,
#  and/or sell copies of the Software, and to permit persons to whom the
#  Software is furnished to do so, subject to the following conditions:
#
#  The above copyright notice and this permission notice shall be included
#  in all copies or substantial portions of the Software.
#
#  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
#  OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
#  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL
#  THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHERtime
#  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
#  FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
#  DEALINGS IN THE SOFTWARE.
#
###############################################################################

class AVNApi:
  """
  the API for handlers/decoders that will input data, decode NMEA or output data
  """
  def log(self, format, *param):
    """
    log infos
    @param format: the format string
    @type format: str
    @param param: the list of parameters
    @return:
    """
    raise NotImplemented()

  def getConfigValue(self, key, default=None):
    """
    get a config item from the avnav_server.xml
    it will be a sub element at AVNLoader with the name of the file and a prefix (buildin,sys,user):
    <AVNLoader>
      <user-test count=5 max=10/>
    <AVNLoader
    @param key: the name of the attribute
    @type  key: str
    @param default: the default value if the attribute is not set
    @return:
    """
    raise NotImplemented()

  def fetchFromQueue(self, sequence, number=10):
    """
    fetch NMEA records from the queue
    initially you should start at sequence 0
    the function will return the last sequence
    caution: if no data is available, the function will immediately return with no data
    the normal flow is:
        seq=0
        while True:
          seq,data=api.fetchFromQueue(seq)
          if len(data) > 0:
            for line in data:
              handle(line)
          else:
            time.sleep(0.1)
    @param sequence: the sequence from which you would like to read the data
    @param number: the max number of records you would like to get
    @return: (sequence,data) - sequence being the last sequence you have, data being a list of nmea data
    """
    raise NotImplemented()

  def addNMEA(self, nmea):
    """
    add NMEA data to the queue
    caution: you should never add a new NMEA record for each record you received by fetchFromQueue
             as the would quickly fill up the queue
    @param nmea: the completely formatted NMEA record
    @type  nmea: str
    @return: None
    """
    raise NotImplemented()

  def addData(self, key, value):
    """
    add a data item (potentially from a decoded NMEA record) to the internal data store
    the data added here later on will be fetched from the GUI
    @param key: the key, it must be one of the keys provided as return from initialize function - see example
    @type  key: str
    @param value: the value to be stored
    @return: True on success, will raise an Exception if the key is not allowed
    """
  def getDataByPrefix(self,prefix):
    """
    get a data item from the internal store
    prefix must be a part of the key (without trailing .)
    @param prefix: the prefix
    @type  prefix: str
    @return: a dict with the values found (potentially hierarchical)
    """
    raise NotImplemented()

  def getSingleValue(self,key):
    """
    get a single value from the store
    @param key: the key
    @type  key: str
    @return: the value
    """
    raise NotImplemented()