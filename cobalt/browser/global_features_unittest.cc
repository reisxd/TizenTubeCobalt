// Copyright 2025 The Cobalt Authors. All Rights Reserved.
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

#include "cobalt/browser/global_features.h"

#include "base/files/file_util.h"
#include "base/files/scoped_temp_dir.h"
#include "base/path_service.h"
#include "base/test/scoped_path_override.h"
#include "base/test/task_environment.h"
#include "base/values.h"
#include "cobalt/browser/constants/cobalt_experiment_names.h"
#include "components/prefs/pref_service.h"
#include "components/prefs/testing_pref_service.h"
#include "components/variations/pref_names.h"
#include "testing/gtest/include/gtest/gtest.h"

namespace cobalt {

class GlobalFeaturesTest : public testing::Test {
 public:
  GlobalFeaturesTest() = default;

 protected:
  // SetUpTestSuite is called once before all tests in this fixture run.
  static void SetUpTestSuite() {
    task_environment_ = std::make_unique<base::test::TaskEnvironment>(
        base::test::TaskEnvironment::MainThreadType::DEFAULT,
        base::test::TaskEnvironment::ThreadPoolExecutionMode::QUEUED);
    temp_dir_ = std::make_unique<base::ScopedTempDir>();
    ASSERT_TRUE(temp_dir_->CreateUniqueTempDir());
    cache_override_ = std::make_unique<base::ScopedPathOverride>(
        base::DIR_CACHE, temp_dir_->GetPath(), true, true);
    // Get the instance once, after the environment is set up.
    instance_ = GlobalFeatures::GetInstance();
  }

  // TearDownTestSuite is called once after all tests in this fixture run.
  static void TearDownTestSuite() {
    // Flush any pending tasks before destroying the environment.
    task_environment_->RunUntilIdle();
    instance_ = nullptr;
    cache_override_.reset();
    temp_dir_.reset();
    task_environment_.reset();
  }

  // SetUp is called before each test.
  void SetUp() override {
    // Clear preferences to ensure test isolation, as the PrefService
    // instance is shared across tests.
    ASSERT_NE(instance_, nullptr);
    auto experiment_config = instance_->experiment_config();
    experiment_config->ClearPref(kExperimentConfig);
    experiment_config->ClearPref(kExperimentConfigActiveConfigData);
    experiment_config->ClearPref(kExperimentConfigFeatures);
    experiment_config->ClearPref(kExperimentConfigFeatureParams);
    experiment_config->ClearPref(kSafeConfig);
    experiment_config->ClearPref(kSafeConfigActiveConfigData);
    experiment_config->ClearPref(kSafeConfigFeatures);
    experiment_config->ClearPref(kSafeConfigFeatureParams);
    // auto metrics_local_state = instance_->metrics_local_state();
  }

  static std::unique_ptr<base::test::TaskEnvironment> task_environment_;
  static std::unique_ptr<base::ScopedTempDir> temp_dir_;
  static std::unique_ptr<base::ScopedPathOverride> cache_override_;
  static GlobalFeatures* instance_;
};

// Initialize static members.
std::unique_ptr<base::test::TaskEnvironment>
    GlobalFeaturesTest::task_environment_;
std::unique_ptr<base::ScopedTempDir> GlobalFeaturesTest::temp_dir_;
std::unique_ptr<base::ScopedPathOverride> GlobalFeaturesTest::cache_override_;
GlobalFeatures* GlobalFeaturesTest::instance_ = nullptr;

TEST_F(GlobalFeaturesTest, DISABLED_CreatesPrefServiceOnInitialization) {
  ASSERT_NE(instance_, nullptr);
  EXPECT_NE(nullptr, instance_->experiment_config());
  EXPECT_NE(nullptr, instance_->metrics_local_state());
  EXPECT_NE(nullptr, instance_->metrics_services_manager());
  EXPECT_NE(nullptr, instance_->metrics_services_manager_client());
  EXPECT_NE(nullptr, instance_->metrics_service());
}

TEST_F(GlobalFeaturesTest, GetInstanceReturnsSameInstance) {
  EXPECT_EQ(instance_, GlobalFeatures::GetInstance());
}

TEST_F(GlobalFeaturesTest, GetMetricsServicesManagerReturnsSameInstance) {
  ASSERT_NE(instance_, nullptr);
  metrics_services_manager::MetricsServicesManager* manager1 =
      instance_->metrics_services_manager();
  metrics_services_manager::MetricsServicesManager* manager2 =
      instance_->metrics_services_manager();
  EXPECT_EQ(manager1, manager2);
  EXPECT_NE(nullptr, manager1);
}

TEST_F(GlobalFeaturesTest, SaveUserAgentOverrideToPersistentPrefs) {
  ASSERT_NE(instance_, nullptr);
  const std::string kUserAgentOverride = "spoofed-ua";

  instance_->SetSettings(GlobalFeatures::kUserAgentOverridePrefName,
                         kUserAgentOverride);

  EXPECT_EQ(kUserAgentOverride,
            instance_->metrics_local_state()->GetString(
                GlobalFeatures::kUserAgentOverridePrefName));
}

TEST_F(GlobalFeaturesTest, RegisterPrefsRegistersExpectedPrefs) {
  TestingPrefServiceSimple pref_service;
  GlobalFeatures::RegisterPrefs(pref_service.registry());

  EXPECT_TRUE(pref_service.FindPreference(kExperimentConfig));
  EXPECT_TRUE(pref_service.FindPreference(kExperimentConfigActiveConfigData));
  EXPECT_TRUE(pref_service.FindPreference(kExperimentConfigFeatures));
  EXPECT_TRUE(pref_service.FindPreference(kExperimentConfigFeatureParams));
  EXPECT_TRUE(pref_service.FindPreference(kSafeConfig));
  EXPECT_TRUE(pref_service.FindPreference(kSafeConfigActiveConfigData));
  EXPECT_TRUE(pref_service.FindPreference(kSafeConfigFeatures));
  EXPECT_TRUE(pref_service.FindPreference(kSafeConfigFeatureParams));
}

TEST_F(GlobalFeaturesTest,
       InitializedActiveConfigDataUnchangedAfterChangeToStoredData) {
  ASSERT_NE(instance_, nullptr);
  auto active_config_data = instance_->active_config_data();
  EXPECT_TRUE(active_config_data.empty());

  base::FilePath config_file =
      temp_dir_->GetPath().Append(FILE_PATH_LITERAL("Experiment Config"));
  base::WriteFile(
      config_file,
      R"({"experiment_config":{"features":{"feature_a":true},"feature_params":{"param1":"value1"},"active_config_data":"ab"},"latest_config_hash":"cd")");

  // Active config data in memory should remain the same.
  active_config_data = instance_->active_config_data();
  EXPECT_TRUE(active_config_data.empty());
}

}  // namespace cobalt
