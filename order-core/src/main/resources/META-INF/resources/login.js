const form = document.getElementById('login-form');
const errorBox = document.getElementById('error');
const submit = document.getElementById('submit');

form.addEventListener('submit', async (event) => {
    event.preventDefault();
    errorBox.className = 'error';
    submit.disabled = true;

    try {
        const response = await fetch('/api/auth/session', {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                username: document.getElementById('username').value.trim(),
                password: document.getElementById('password').value
            })
        });

        if (!response.ok) {
            const body = await response.json().catch(() => null);
            throw new Error(body?.detail || body?.message || `HTTP ${response.status}`);
        }

        window.location.replace('/');
    } catch (error) {
        errorBox.textContent = error.message;
        errorBox.className = 'error show';
        submit.disabled = false;
    }
});
