// Copyright 2015 The Cobalt Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#ifndef COBALT_NETWORK_NETWORK_MODULE_H_
#define COBALT_NETWORK_NETWORK_MODULE_H_

#include <memory>
#include <string>
#include <vector>

#include "base/task/sequenced_task_runner.h"
#include "base/threading/thread.h"
#include "cobalt/base/event_dispatcher.h"
#include "cobalt/network/cobalt_net_log.h"
#include "cobalt/network/cookie_jar_impl.h"
#include "cobalt/network/net_poster.h"
#include "cobalt/network/network_delegate.h"
#include "cobalt/network/url_request_context.h"
#include "cobalt/network/url_request_context_getter.h"
#include "cobalt/persistent_storage/persistent_settings.h"
#include "cobalt/storage/storage_manager.h"
#include "url/gurl.h"
#if defined(DIAL_SERVER)
// Including this header causes a link error on Windows, since we
// don't have StreamListenSocket.
#include "cobalt/network/dial/dial_service.h"
#endif

namespace base {
class WaitableEvent;
}  // namespace base

namespace cobalt {
namespace network {

// Used to differentiate type of network call for Client Hint Headers.
// Values correspond to bit masks against |kEnabledClientHintHeaders|.
enum ClientHintHeadersCallType : int32_t {
  kCallTypeLoader = (1u << 0),
  kCallTypeMedia = (1u << 1),
  kCallTypePost = (1u << 2),
  kCallTypePreflight = (1u << 3),
  kCallTypeUpdater = (1u << 4),
  kCallTypeXHR = (1u << 5),
};

// Determines which type of network calls should include Client Hint Headers.
constexpr int32_t kEnabledClientHintHeaders = (kCallTypeLoader | kCallTypeXHR);

const char kQuicEnabledPersistentSettingsKey[] = "QUICEnabled";
const char kHttp2EnabledPersistentSettingsKey[] = "HTTP2Enabled";
const char kHttp3EnabledPersistentSettingsKey[] = "HTTP3Enabled";
const char kProtocolFilterKey[] = "httpProtocolFilter";

class NetworkSystem;
// NetworkModule wraps various networking-related components such as
// a URL request context. This is owned by BrowserModule.
class NetworkModule : public base::CurrentThread::DestructionObserver {
 public:
  struct Options {
    Options()
        : cookie_policy(net::StaticCookiePolicy::BLOCK_ALL_THIRD_PARTY_COOKIES),
          ignore_certificate_errors(false),
          https_requirement(network::kHTTPSRequired),
          cors_policy(network::kCORSRequired),
          preferred_language("en-US"),
          max_network_delay_usec(0),
          persistent_settings(nullptr) {}
    net::StaticCookiePolicy::Type cookie_policy;
    bool ignore_certificate_errors;
    HTTPSRequirement https_requirement;
    network::CORSPolicy cors_policy;
    std::string preferred_language;
    std::string custom_proxy;
    int64_t max_network_delay_usec;
    persistent_storage::PersistentSettings* persistent_settings;
    storage::StorageManager::Options storage_manager_options;
  };

  // Simple constructor intended to be used only by tests.
  NetworkModule() : NetworkModule(Options()) {}
  explicit NetworkModule(const Options& options);

  // Constructor for production use.
  NetworkModule(const std::string& user_agent_string,
                const std::vector<std::string>& client_hint_headers,
                base::EventDispatcher* event_dispatcher,
                const Options& options = Options());
  ~NetworkModule();

  // Ensures that the storage manager is created.
  void EnsureStorageManagerStarted();

  URLRequestContext* url_request_context() const {
    return url_request_context_.get();
  }
  NetworkDelegate* network_delegate() const { return network_delegate_; }
  std::string GetUserAgent() const;
  const std::string& preferred_language() const {
    return options_.preferred_language;
  }
  int64_t max_network_delay_usec() const {
    return options_.max_network_delay_usec;
  }
  scoped_refptr<URLRequestContextGetter> url_request_context_getter() const {
    return url_request_context_getter_;
  }
  scoped_refptr<base::SequencedTaskRunner> task_runner() const {
    return thread_->task_runner();
  }
  storage::StorageManager* storage_manager() const {
    return storage_manager_.get();
  }
  network_bridge::CookieJar* cookie_jar() const { return cookie_jar_.get(); }
  network_bridge::PostSender GetPostSender() const;
#if defined(DIAL_SERVER)
  scoped_refptr<network::DialServiceProxy> dial_service_proxy() const {
    return dial_service_proxy_;
  }
#endif
  void SetProxy(const std::string& custom_proxy_rules);

  void SetEnableQuicFromPersistentSettings();
  void SetEnableHttp2FromPersistentSettings();
  void SetEnableHttp3FromPersistentSettings();
  bool SetHttpProtocolFilterPersistentSetting(const std::string&);
  void SetProtocolFilterFromPersistentSettings();
  void SetQuicConnectionOptions(const std::string&);
  void SetQuicClientConnectionOptions(const std::string&);

  // Adds the Client Hint Headers to the provided URLFetcher if enabled.
  void AddClientHintHeaders(net::URLFetcher& url_fetcher,
                            ClientHintHeadersCallType call_type) const;

  // From base::CurrentThread::DestructionObserver.
  void WillDestroyCurrentMessageLoop() override;

  // Used to capture NetLog from Devtools
  void StartNetLog();
  base::FilePath StopNetLog();

#if defined(DIAL_SERVER)
  void RestartDialService();
#endif

 private:
  void Initialize(const std::string& user_agent_string,
                  base::EventDispatcher* event_dispatcher);
  void OnCreate(base::WaitableEvent* creation_event,
                net::HttpUserAgentSettings* http_user_agent_settings);
  std::unique_ptr<network_bridge::NetPoster> CreateNetPoster();

  std::vector<std::string> client_hint_headers_;
  std::unique_ptr<storage::StorageManager> storage_manager_;
  std::unique_ptr<base::Thread> thread_;
  std::unique_ptr<URLRequestContext> url_request_context_;
  scoped_refptr<URLRequestContextGetter> url_request_context_getter_;
  NetworkDelegate* network_delegate_;
  std::unique_ptr<NetworkSystem> network_system_;
  std::unique_ptr<network_bridge::CookieJar> cookie_jar_;
#if defined(DIAL_SERVER)
  void OnRestartDialService(base::WaitableEvent* creation_event);
  std::unique_ptr<network::DialService> dial_service_;
  scoped_refptr<network::DialServiceProxy> dial_service_proxy_;
#endif
  std::unique_ptr<network_bridge::NetPoster> net_poster_;

  base::FilePath net_log_path_;
#if defined(ENABLE_NETWORK_LOGGING)
  std::unique_ptr<CobaltNetLog> net_log_{nullptr};
#endif
  Options options_;
  bool protocol_filter_update_pending_;

  DISALLOW_COPY_AND_ASSIGN(NetworkModule);
};

}  // namespace network
}  // namespace cobalt

#endif  // COBALT_NETWORK_NETWORK_MODULE_H_
