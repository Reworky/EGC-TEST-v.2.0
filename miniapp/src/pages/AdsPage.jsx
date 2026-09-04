import BackButton from '../components/BackButton';
import AdRewardCard from '../components/AdRewardCard';
import './QuestsPage.css';

export default function AdsPage() {
  return (
    <div className="quests-page">
      <div style={{ padding: '16px 16px 0' }}><BackButton to="/quests" label="Квесты" /></div>
      <div className="category-section" style={{ paddingTop: 16 }}>
        <AdRewardCard />
      </div>
    </div>
  );
}
