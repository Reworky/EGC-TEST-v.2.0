import BackButton from '../components/BackButton';
import AdRewardCard from '../components/AdRewardCard';
import './QuestsPage.css';
import './ShopPage.css';
import './ReferralsPage.css';
import './WalletPage.css';

export default function AdsPage() {
  return (
    <div className="quests-page shop-page">
      <div style={{ padding: '16px 16px 0' }}><BackButton to="/quests" label="Квесты" /></div>
      <div style={{ margin: '16px' }}>
        <AdRewardCard />
      </div>
    </div>
  );
}
