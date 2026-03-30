# Copyright 2019 The Cobalt Authors. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Contains a threaded web server used for web platform tests."""

import os
import sys
import threading
import time
import uuid

SRC_DIR = os.path.join(os.path.dirname(__file__), os.pardir, os.pardir)
WPT_DIR = os.path.join(SRC_DIR, 'third_party', 'web_platform_tests')
sys.path.insert(0, WPT_DIR)

# pylint: disable=wrong-import-position
from tools.serve import serve
from tools.wptserve.wptserve import stash


class WebPlatformTestServer(object):
  """Runs a WPT StashServer on its own thread in a Python context manager."""

  def __init__(self, binding_address=None, wpt_http_port=None):
    # IP config['host'] should map to either through a dns or the hosts file.
    if binding_address:
      self._binding_address = binding_address
    else:
      self._binding_address = '127.0.0.1'
    if wpt_http_port:
      self._wpt_http_port = wpt_http_port
    else:
      self._wpt_http_port = '8000'

  def main(self):
    kwargs = vars(serve.get_parser().parse_args())

    # Configuration script to setup server used for serving web platform tests.
    config = serve.load_config(
        os.path.join(WPT_DIR, 'config.default.json'),
        os.path.join(WPT_DIR, 'config.json'), **kwargs)
    config['ports']['http'][0] = int(self._wpt_http_port)

    serve.setup_logger(config['log_level'])

    with stash.StashServer((self._binding_address, serve.get_port()),
                           authkey=str(uuid.uuid4())):
      with serve.get_ssl_environment(config) as ssl_env:
        _, self._servers = serve.start(config, ssl_env, serve.default_routes(),
                                       **kwargs)
        self._server_started.set()

        try:
          while any(
              item.is_alive() for item in serve.iter_procs(self._servers)):
            for item in serve.iter_procs(self._servers):
              item.join(1)
        except KeyboardInterrupt:
          serve.logger.info('Shutting down')

  def __enter__(self):
    self._server_started = threading.Event()
    self._server_thread = threading.Thread(target=self.main)
    self._server_thread.start()
    self._server_started.wait()
    return self

  def __exit__(self, exc_type, exc_value, traceback):
    for servers in self._servers.values():
      for _, server in servers:
        server.kill()
    self._server_thread.join()
