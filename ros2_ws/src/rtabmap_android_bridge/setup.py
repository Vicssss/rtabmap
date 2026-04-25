from setuptools import setup

package_name = 'rtabmap_android_bridge'

setup(
    name=package_name,
    version='0.1.0',
    packages=[package_name],
    data_files=[
        ('share/ament_index/resource_index/packages', ['resource/' + package_name]),
        ('share/' + package_name, ['package.xml']),
    ],
    install_requires=['setuptools'],
    zip_safe=True,
    maintainer='RTAB-Map Bridge',
    maintainer_email='devnull@example.com',
    description='Android StreamOnly websocket bridge for ROS 2 Humble.',
    license='BSD-3-Clause',
    tests_require=['pytest'],
    entry_points={
        'console_scripts': [
            'stream_bridge_node = rtabmap_android_bridge.stream_bridge_node:main',
        ],
    },
)
